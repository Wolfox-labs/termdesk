/**
 * Unified live conversation pipeline — one chat surface, selectable engine.
 *
 * Product rule: there is one conversation pipeline (`chat.*`). The engine is a
 * per-chat choice, not a separate "task" product:
 *
 *   engine = 'dsh'   (default)  Live chat sessions over the DSH SDK runtime.
 *   engine = 'codex'            `codex exec` turns continued with
 *                               `codex exec resume <thread_id>` — the same
 *                               multi-turn mechanism the old `ai.*` task
 *                               path used, moved onto the chat surface.
 *
 * The separate `ai.*` task pipeline in engines.js is deprecated; it remains
 * for wire compatibility only.
 *
 * --- DSH SDK notes (engine = 'dsh') ---
 *
 * Why this exists: `dsh --profile headless "<task>"` is one-shot. It answered a
 * prompt, exited, and had no memory of anything (verified by probe). The Desktop
 * app's own conversation could not be attached to either — its backend is an
 * in-process Cordis graph plus a Tauri shell, and its HTTP surface is a browser
 * BFF, not a chat API.
 *
 * What DSH does ship is `--profile sdk`: a long-lived stdio JSON-RPC runtime
 * (@deepseek-ai/dsh-sdk-protocol). One process holds one live agent, and
 * `session/prompt` with the SAME sessionId continues the SAME conversation.
 * That is a real session, so TermDesk keeps one child per chat instead of
 * re-feeding a transcript into a fresh process the way the old codex/headless
 * path had to.
 *
 * Wire contract (one JSON-RPC 2.0 message per newline-terminated line):
 *   client -> server : initialize | session/prompt | shutdown
 *   server -> client : session.event | session.status
 *                      | subagent.started | subagent.finished
 *
 * Verified by tools/probes/sdk-probe.js: handshake, prompt receipt, live assistant
 * text, and turn-2 continuity on one session id.
 *
 * Lifecycle notes that shaped the design below:
 *   - `initialize` fixes cwd/provider/model for the whole process, so it runs
 *     once, before the first prompt, and a chat cannot change route mid-life.
 *   - There is no cancel method on the wire. Cancelling a turn therefore means
 *     disposing the runtime, which also ends the conversation, so `cancel` is
 *     reported honestly as "stop this chat" rather than pretending a turn can
 *     be aborted in place.
 *   - Idle runtimes are reaped, because each one is a full Node harness holding
 *     an open provider route.
 *
 * --- Codex notes (engine = 'codex') ---
 *
 * Codex has no resident runtime on the wire. Each turn spawns `codex exec --json`
 * (or `codex exec resume <thread_id> --json` once the thread is known) and
 * streams newline-delimited JSON events. Continuity is genuine: the thread id
 * returned by `thread.started` is reused for every later turn, which is the
 * documented Codex resume mechanism — not a re-fed transcript.
 *
 * Events are mapped onto the SAME chat event vocabulary as DSH
 * (message / reasoning / tool / command / turn / …) so the phone renders both
 * engines through one schema. Mapped records never carry a `type` field:
 * `encodeFrame` spreads payload after `type`, so a top-level `type` in a
 * payload would silently overwrite the frame's own wire type.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import crypto from 'node:crypto';

import { CodexAppServer, notificationToChatEvents, turnToChatEvents } from './kernels/codex.js';
import { dshEventToChatEvent } from './sessions.js';

const MAX_CHATS = 8;
const MAX_EVENTS_PER_CHAT = 1200;
const MAX_PROMPT_CHARS = 24000;
const IDLE_TTL_MS = 45 * 60 * 1000;
const INIT_TIMEOUT_MS = 120 * 1000;
const PROMPT_TIMEOUT_MS = 30 * 60 * 1000;
const REAP_INTERVAL_MS = 60 * 1000;

/** Engines a chat conversation can run on. */
export const CHAT_ENGINES = ['codex', 'dsh'];

/** Resolve the DSH launcher entry script. Mirrors engines.js so tests can override both. */
function findDsh() {
  if (process.env.TERMDESK_DSH) return process.env.TERMDESK_DSH;
  return path.join(
    os.homedir(),
    'AppData',
    'Roaming',
    'io.github.hairyf.deepseek-harness-desktop',
    'dependencies',
    'dsh',
    'node_modules',
    '@deepseek-ai',
    'dsh',
    'lib',
    'bin.js',
  );
}

/**
 * Default provider/model route.
 *
 * `deepseek-official` is the only provider the SDK runtime can mount on its own
 * (it loads the DeepSeek adapter when no adapter is registered). Any other id
 * must already be configured in the profile, which is why the route is
 * configurable instead of hard-coded to one host's setup.
 */
function defaultRoute() {
  return {
    provider: process.env.TERMDESK_CHAT_PROVIDER || 'wolfox',
    model: process.env.TERMDESK_CHAT_MODEL || 'spe/deepseek-v4.1-flash',
  };
}

let chatCounter = 0;

/**
 * Map one Codex `codex exec --json` line onto the shared chat event vocabulary.
 *
 * Returns null for lines that carry no user-visible conversation content.
 * The returned record uses `kind` (never `type`) so it is safe to embed in a
 * `chat.event` payload — see the encodeFrame note in the file header.
 *
 * @param {object} ev parsed Codex JSONL event
 * @returns {{kind, role, text, name, meta}|null}
 */
/** One live conversation: one engine runtime plus its transcript. */
class Chat {
  constructor({ id, title, cwd, engine, provider, model, threadId = null, nativeSessionId = null }) {
    this.id = id;
    this.title = title;
    this.cwd = cwd;
    /** 'codex' | 'dsh' — which engine backs this conversation. */
    this.engine = engine;
    this.provider = provider;
    this.model = model;
    this.createdAt = Date.now();
    this.lastUsedAt = Date.now();
    /** running | idle | stopped | failed */
    this.status = 'idle';
    /**
     * True once the engine can accept prompts.
     * dsh: initialize handshake finished. codex: no resident runtime, so this
     * becomes true after the first successful spawn (there is nothing to
     * handshake with before the first turn).
     */
    this.ready = false;
    /**
     * Durable conversation handle.
     * dsh: the SDK wire session id (also the runtime's identity).
     * codex: the Codex thread id used for `exec resume`; null until the first
     * `thread.started` event. `sessionId` keeps the same wire field name so
     * clients do not need an engine-specific key.
     */
    this.sessionId = nativeSessionId ?? threadId ?? `termdesk-${id}-${Date.now().toString(36)}`;
    this.nativeResume = Boolean(nativeSessionId);
    this.runtimeStarted = false;
    /** Codex thread id (engine=codex only), kept separately for resume. */
    this.threadId = threadId;
    this.events = [];
    this.seq = 0;
    this.lastError = null;
    this.usage = null;
    this.titleSource = 'prompt';
    this.child = null;
    this.pending = new Map();
    this.nextRpcId = 1;
    this.lineCount = 0;
    /**
     * Streamed assistant text events for the current turn, in order.
     *
     * Kept separately from `events` because `assistant/chunk` deltas are only a
     * preview of the message the runtime sends when the block finishes. The
     * previews must be removable once that authoritative message arrives,
     * otherwise every answer would be stored twice.
     */
    this.previews = [];
    /** The block currently receiving deltas, or null. */
    this.streaming = null;
    /**
     * The user's optimistically-shown message awaiting the runtime's echo.
     *
     * Cleared once the runtime confirms it, so a later identical message from
     * the user is not mistaken for the echo of this one. Only used on the DSH
     * path, which echoes user messages back over the wire.
     */
    this.pendingUserEcho = null;
  }

  push(event) {
    this.seq += 1;
    const record = { seq: this.seq, at: Date.now(), ...event };
    this.events.push(record);
    if (this.events.length > MAX_EVENTS_PER_CHAT) {
      this.events.splice(0, this.events.length - MAX_EVENTS_PER_CHAT);
    }
    return record;
  }

  summary() {
    return {
      id: this.id,
      title: this.title,
      cwd: this.cwd,
      engine: this.engine,
      provider: this.provider,
      model: this.model,
      status: this.status,
      ready: this.ready,
      sessionId: this.sessionId,
      threadId: this.threadId,
      createdAt: this.createdAt,
      lastUsedAt: this.lastUsedAt,
      eventCount: this.events.length,
      lastError: this.lastError,
      usage: this.usage,
      live: Boolean(this.child && this.child.exitCode === null),
    };
  }

  detail(afterSeq = 0) {
    return {
      ...this.summary(),
      events: this.events.filter((e) => e.seq > afterSeq),
    };
  }
}

export class ChatManager {
  constructor() {
    this.chats = new Map();
    this.onEvent = null;
    // The reaper belongs to the manager, not to a socket: a chat runtime must
    // keep its idle clock running while the phone is disconnected, exactly as a
    // terminal session keeps its scrollback.
    this.reaper = setInterval(() => this.reapIdle(), REAP_INTERVAL_MS);
    this.reaper.unref?.();
    /**
     * One shared Codex app-server for every Codex chat on this PC.
     *
     * The kernel owns session identity, the transcript and the turn lifecycle,
     * so TermDesk keeps no per-chat Codex process any more.
     */
    this.codex = null;
    /** native thread id -> Chat, for routing app-server notifications. */
    this.codexThreads = new Map();
  }

  /** Route chat events to the currently connected client. */
  attach(onEvent) {
    this.onEvent = onEvent;
  }

  /** Stop routing to a socket that went away; live chats survive the disconnect. */
  detach() {
    this.onEvent = null;
  }

  emit(payload) {
    try {
      this.onEvent?.(payload);
    } catch {
      // A dead socket must never take the runtime down with it.
    }
  }

  list() {
    return [...this.chats.values()]
      .map((c) => c.summary())
      .sort((a, b) => b.lastUsedAt - a.lastUsedAt);
  }

  get(id) {
    return this.chats.get(id) ?? null;
  }

  /** Release runtimes nobody has touched for a while. */
  reapIdle() {
    const now = Date.now();
    for (const chat of [...this.chats.values()]) {
      if (chat.status === 'running') continue;
      if (now - chat.lastUsedAt < IDLE_TTL_MS) continue;
      this.dispose(chat, 'idle-timeout');
    }
  }

  /** Remove an idle chat entirely, so long-lived agents do not accumulate. */
  dropIdleChat() {
    const idle = [...this.chats.values()]
      .filter((c) => c.status !== 'running')
      .sort((a, b) => a.lastUsedAt - b.lastUsedAt);
    if (idle.length >= MAX_CHATS) this.dispose(idle[0], 'capacity');
  }

  create({ cwd, provider, model, title, engine }) {
    const eng = engine == null || engine === '' ? 'dsh' : String(engine);
    if (!CHAT_ENGINES.includes(eng)) {
      return {
        ok: false,
        code: 'bad_engine',
        message: `不支持的引擎 "${engine}"（可选：${CHAT_ENGINES.join(' / ')}）`,
      };
    }
    this.dropIdleChat();
    chatCounter += 1;
    const id = `c-${crypto.randomUUID()}`;
    const route = eng === 'dsh' ? defaultRoute() : { provider: null, model: null };
    const workdir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
    const chat = new Chat({
      id,
      title: (title && String(title).trim()) || '新对话',
      cwd: workdir,
      engine: eng,
      provider: provider || route.provider,
      model: model || route.model,
    });
    this.chats.set(id, chat);
    chat.push({ kind: 'local', role: 'engine', text: `会话已创建 · ${chat.engine} · ${chat.cwd}` });
    if (chat.provider || chat.model) {
      chat.push({
        kind: 'local',
        role: 'engine',
        text: `模型 ${chat.provider ?? '-'} / ${chat.model ?? '-'}`,
      });
    } else if (eng === 'codex') {
      // No explicit route: Codex uses whatever its config.toml selects, which
      // is the same surface codex.get / codex.apply manage.
      chat.push({ kind: 'local', role: 'engine', text: '模型 随 Codex 配置（codex.get / codex.apply）' });
    }
    return { ok: true, chat: chat.summary() };
  }

  /** Adopt a recorded native session; the display transcript is never used as a prompt. */
  async resume({ engine, id, sessionPath }) {
    if (!CHAT_ENGINES.includes(engine)) return { ok: false, code: 'bad_engine', message: '不支持的引擎' };
    if (typeof id !== 'string' || !/^[a-zA-Z0-9_-]{1,160}$/.test(id)) {
      return { ok: false, code: 'bad_session', message: '会话标识无效' };
    }
    const existing = [...this.chats.values()].find(c => c.engine === engine && c.sessionId === id);
    if (existing) return { ok: true, chat: existing.detail() };
    if (engine === 'dsh') return { ok: false, code: 'resume_unsupported', message: '当前 DSH SDK 尚未提供经过验证的原生恢复入口；为避免丢失上下文，本版不伪造续聊' };
    if (engine === 'codex') return this.resumeCodex(id);
    // Every resumable engine now goes through its own adapter; there is no
    // generic fallback that reconstructs a session out of display text.
    return { ok: false, code: 'resume_unsupported', message: `${engine} 内核未提供经过验证的恢复接口` };
  }
  /**
   * Send one user message and start the turn.
   *
   * Resolves as soon as the engine accepts the prompt (a durable enqueue
   * receipt), not when the answer is done: the answer arrives as streamed
   * `chat.event` frames over the same socket the request came in on.
   *
   * Engine dispatch:
   *   dsh   — resident SDK runtime, one `session/prompt` per turn.
   *   codex — one `codex exec` (or `exec resume <thread_id>`) per turn.
   */
  async send(id, text) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    const message = String(text ?? '').trim();
    if (message.length === 0) return { ok: false, code: 'empty_prompt', message: '消息不能为空' };
    if (message.length > MAX_PROMPT_CHARS) {
      return { ok: false, code: 'prompt_too_long', message: `消息过长（上限 ${MAX_PROMPT_CHARS} 字符）` };
    }
    if (chat.status === 'running') {
      return { ok: false, code: 'busy', message: '上一轮还在进行中' };
    }

    chat.lastUsedAt = Date.now();
    chat.status = 'running';

    // Show the user's own message immediately, before the engine answers. Both
    // engines echo it back (DSH as `user/message`, Codex app-server as a
    // `userMessage` thread item), so the optimistic line is reconciled against
    // that echo instead of being shown twice.
    const userSeq = chat.push({ kind: 'message', role: 'user', text: message, optimistic: true });
    chat.pendingUserEcho = userSeq;
    if (chat.titleSource === 'prompt') {
      const firstLine = message.split(/\r?\n/)[0].slice(0, 60);
      if (firstLine.trim()) {
        chat.title = firstLine.trim();
        chat.titleSource = 'user';
      }
    }

    if (chat.engine === 'codex') {
      return this.sendCodex(chat, message, userSeq);
    }
    return this.sendDsh(chat, message, userSeq);
  }

  /** One turn on the resident DSH SDK runtime. */
  async sendDsh(chat, message, userSeq) {
    try {
      await this.ensureRuntime(chat);
    } catch (err) {
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `无法启动 DSH 运行时：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'spawn_failed', message: chat.lastError, userSeq };
    }

    chat.status = 'running';
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    this.emitEvent(chat, userSeq);

    let receipt;
    try {
      receipt = await chat.request(
        'session/prompt',
        { sessionId: chat.sessionId, contentBlocks: [{ type: 'text', text: message }] },
        PROMPT_TIMEOUT_MS,
      );
    } catch (err) {
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `发送失败：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'prompt_failed', message: chat.lastError, userSeq };
    }

    chat.runtimeStarted = true;
    return { ok: true, userSeq, messageId: receipt?.messageId ?? null, sessionId: chat.sessionId };
  }

  /**
   * One Codex turn, driven through the kernel's own app-server.
   *
   * The thread IS the session: a fresh chat starts one (`thread/start`), an
   * opened history entry is attached (`thread/resume`). Both then send through
   * `turn/start` and receive the answer as notifications. No transcript is ever
   * re-fed, and TermDesk keeps no per-chat Codex process.
   */
  async sendCodex(chat, message, userSeq) {
    const server = this.codexServer();
    try {
      if (!chat.threadId) {
        const started = await server.startThread({
          cwd: chat.cwd,
          ...(chat.model ? { model: chat.model } : {}),
          ...(chat.provider ? { modelProvider: chat.provider } : {}),
        });
        chat.threadId = started?.thread?.id ?? null;
        chat.sessionId = chat.threadId ?? chat.sessionId;
        if (!chat.threadId) throw new Error('thread/start 未返回会话标识');
      } else {
        // A thread this app-server already holds cannot be resumed a second
        // time; that is not an error, the next turn simply continues it.
        await server.resumeThread(chat.threadId, { cwd: chat.cwd }).catch((err) => {
          if (!/active writer/i.test(String(err?.message ?? err))) throw err;
        });
      }
      this.codexThreads.set(chat.threadId, chat);

      chat.ready = true;
      chat.status = 'running';
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });

      this.armCodexWatchdog(chat);
      const turn = await server.startTurn(chat.threadId, message);
      chat.currentTurnId = turn?.turn?.id ?? null;
      return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
    } catch (err) {
      this.clearCodexWatchdog(chat);
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `Codex 调用失败：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      return { ok: false, code: 'prompt_failed', message: chat.lastError, userSeq };
    }
  }

  // --- Codex app-server plumbing -------------------------------------------

  /** Lazily start the shared app-server and wire notification routing. */
  codexServer() {
    if (!this.codex) {
      const server = new CodexAppServer({ log: (line) => console.log(line) });
      server.on('notification', (method, params) => this.handleCodexNotification(method, params));
      server.on('serverRequest', (method) => {
        console.error(`[codex] 引擎请求未实现，已拒绝：${method}`);
      });
      server.on('exit', (code) => {
        for (const chat of this.chats.values()) {
          if (chat.engine !== 'codex' || chat.status !== 'running') continue;
          this.clearCodexWatchdog(chat);
          chat.status = 'failed';
          chat.lastError = `codex app-server 退出（${code}）`;
          chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
          this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
          this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
        }
        this.codex = null;
      });
      this.codex = server;
    }
    return this.codex;
  }

  /**
   * Adopt a Codex thread: attach the kernel to it and load the transcript the
   * kernel already holds. This is what makes "打开历史" and "新建对话" the same
   * path — only the reference differs (existing id vs none).
   */
  async resumeCodex(id) {
    const server = this.codexServer();
    let thread = null;
    try {
      thread = (await server.resumeThread(id, {}))?.thread ?? null;
    } catch (err) {
      const message = String(err?.message ?? err);
      // The kernel refuses a thread that another process is actively writing
      // (the Codex desktop app holding a conversation open). Say so instead of
      // pretending the history is unavailable.
      if (/active writer/i.test(message)) {
        const mine = await server.listLoadedThreads().catch(() => []);
        if (!mine.includes(id)) {
          return { ok: false, code: 'session_busy', message: '该会话正在电脑端被使用，手机上暂时无法接手' };
        }
      }
      thread = await server.readThread(id, { includeTurns: true }).catch(() => null);
      if (!thread) return { ok: false, code: 'no_session', message: `内核无法恢复该会话：${message}` };
    }
    if (!thread?.id) return { ok: false, code: 'no_session', message: '找不到匹配的原生会话' };
    if (!thread.turns) thread = (await server.readThread(id, { includeTurns: true })) ?? thread;

    const cwd = thread.cwd;
    if (!cwd || !fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory()) {
      return { ok: false, code: 'missing_workspace', message: '原会话的工作目录不存在，不能静默切换到其他目录' };
    }
    const adopted = [...this.chats.values()].find((c) => c.engine === 'codex' && c.sessionId === id);
    if (adopted) return { ok: true, chat: adopted.detail() };

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: thread.name || (thread.preview ?? '').split('\n')[0].slice(0, 60) || '历史对话',
      cwd,
      engine: 'codex',
      provider: thread.modelProvider ?? null,
      model: thread.model ?? null,
      threadId: id,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    const created = typeof thread.createdAt === 'number' ? thread.createdAt : Date.now();
    chat.createdAt = created < 1e12 ? created * 1000 : created;
    for (const turn of thread.turns ?? []) {
      for (const event of turnToChatEvents(turn)) chat.push(event);
    }
    chat.push({ kind: 'local', role: 'engine', text: '已恢复原生会话 · 后续消息延续原上下文' });
    this.chats.set(chat.id, chat);
    this.codexThreads.set(id, chat);
    return { ok: true, chat: chat.detail() };
  }

  /** Route one app-server notification into the right chat transcript. */
  handleCodexNotification(method, params) {
    const threadId = params?.threadId ?? null;
    const chat = threadId ? this.codexThreads.get(threadId) : null;

    if (method === 'turn/started') { if (chat) chat.currentTurnId = params?.turn?.id ?? null; return; }
    if (method === 'turn/completed') { if (chat) this.finishCodexTurn(chat, params?.turn ?? {}); return; }
    if (!chat || chat.status === 'stopped') return;

    if (method === 'thread/tokenUsage/updated' && params?.usage) chat.usage = params.usage;
    for (const event of notificationToChatEvents(method, params)) {
      if (event.kind === 'turn') continue; // lifecycle handled above
      if (event.kind === 'message' && event.role === 'user') {
        // The kernel echoes the user's own message back as a thread item. The
        // phone already shows that line (optimistic), so confirm it and drop
        // the echo — otherwise the user's words appear twice.
        const pending = chat.pendingUserEcho;
        if (pending && pending.text.trim() === event.text.trim()) {
          chat.pendingUserEcho = null;
          pending.optimistic = false;
          this.emitEvent(chat, pending);
        }
        continue;
      }
      if (event.streaming) {
        this.discardPreviews(chat, event.kind);
        const record = chat.push(event);
        chat.previews.push(record);
        this.emitEvent(chat, record, { stream: true });
        continue;
      }
      if (event.kind === 'message' || event.kind === 'reasoning') this.discardPreviews(chat, event.kind);
      this.pushAndEmit(chat, event);
    }
  }

  /** Close out a finished turn exactly once. */
  finishCodexTurn(chat, turn) {
    if (chat.status === 'stopped') return;
    this.clearCodexWatchdog(chat);
    chat.currentTurnId = null;
    const failed = turn?.status === 'failed';
    const cancelled = turn?.status === 'interrupted';
    this.discardPreviews(chat, 'message');
    this.discardPreviews(chat, 'reasoning');
    if (failed) {
      chat.status = 'failed';
      chat.lastError = turn?.error?.message ?? '回合失败';
      chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
    } else {
      chat.status = 'idle';
      chat.lastError = null;
    }
    chat.push({
      kind: 'turn', role: 'engine', text: '',
      meta: { state: failed ? 'failed' : 'ended', reason: cancelled ? 'interrupted' : null },
    });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: failed ? 'failed' : 'ended' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
  }

  armCodexWatchdog(chat) {
    this.clearCodexWatchdog(chat);
    chat.codexTimer = setTimeout(() => {
      if (chat.status !== 'running') return;
      chat.status = 'failed';
      chat.lastError = 'turn timeout';
      chat.push({ kind: 'error', role: 'engine', text: '回复超时（30 分钟）' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    }, PROMPT_TIMEOUT_MS);
    chat.codexTimer.unref?.();
  }

  clearCodexWatchdog(chat) {
    if (chat.codexTimer) { clearTimeout(chat.codexTimer); chat.codexTimer = null; }
  }

  /**
   * Stop the in-flight turn.
   *
   * dsh: there is no wire cancel, so this disposes the runtime (the
   *   conversation itself ends — reported honestly, not as a soft abort).
   * codex: the turn is one `codex exec` process, so killing it stops the
   *   reply while the Codex thread stays resumable for the next message.
   */
  cancel(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.status !== 'running') return { ok: false, code: 'not_running', message: '当前没有进行中的回复' };

    if (chat.engine === 'codex') {
      // The kernel cancels its own turn; the thread stays resumable.
      chat.status = 'stopped';
      if (chat.threadId) this.codex?.interruptTurn(chat.threadId).catch(() => {});
      chat.push({ kind: 'error', role: 'engine', text: '已停止本轮回复' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'stopped' });
      return { ok: true };
    }

    this.dispose(chat, 'cancelled');
    chat.status = 'stopped';
    chat.push({ kind: 'error', role: 'engine', text: '已停止（DSH 协议无单轮取消，运行时已终止）' });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'stopped' });
    return { ok: true };
  }

  /** Forget a chat and release its runtime. */
  close(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.status === 'running') {
      return { ok: false, code: 'busy', message: '正在回复中，请先停止' };
    }
    this.dispose(chat, 'closed');
    this.chats.delete(id);
    this.emit({ event: 'chat.closed', chatId: id });
    return { ok: true };
  }

  disposeAll() {
    for (const chat of [...this.chats.values()]) this.dispose(chat, 'shutdown');
    try { this.codex?.dispose(); } catch { /* already gone */ }
    this.codex = null;
    this.codexThreads.clear();
    if (this.reaper) {
      clearInterval(this.reaper);
      this.reaper = null;
    }
    this.onEvent = null;
  }

  // --- runtime plumbing --------------------------------------------------

  /** Start the DSH SDK runtime for a chat and complete its handshake once. */
  async ensureRuntime(chat) {
    if (chat.child && chat.child.exitCode === null && chat.ready) return;

    const bin = findDsh();
    if (!fs.existsSync(bin)) {
      throw new Error(`找不到 DSH 入口：${bin}`);
    }

    const child = spawn(process.execPath, [bin, '--profile', 'sdk'], {
      cwd: chat.cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    chat.child = child;
    chat.ready = false;

    child.on('error', (err) => {
      chat.lastError = err.message;
      chat.status = 'failed';
      this.failPending(chat, new Error(err.message));
      chat.push({ kind: 'error', role: 'engine', text: `运行时启动失败：${err.message}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
    });

    child.on('close', (code) => {
      chat.ready = false;
      this.failPending(chat, new Error(`DSH 运行时已退出（退出码 ${code}）`));
      if (chat.status === 'shutting-down') return;
      if (chat.status === 'running') {
        chat.status = 'failed';
        chat.push({ kind: 'error', role: 'engine', text: `运行时意外退出（退出码 ${code}）` });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      }
      this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
    });

    // stdout is the protocol channel; stderr is free-form launcher/agent noise.
    const rl = readline.createInterface({ input: child.stdout });
    rl.on('line', (line) => this.handleLine(chat, line));
    const rlErr = readline.createInterface({ input: child.stderr });
    rlErr.on('line', (line) => this.handleStderr(chat, line));

    // initialize fixes cwd/provider/model for the lifetime of the process.
    await chat.request(
      'initialize',
      { cwd: chat.cwd, provider: chat.provider, model: chat.model },
      INIT_TIMEOUT_MS,
    );
    chat.ready = true;
    chat.lastError = null;
    chat.push({ kind: 'local', role: 'engine', text: '运行时已就绪（原生会话）' });
  }

  /** Route one inbound JSON-RPC frame. */
  handleLine(chat, line) {
    const trimmed = line.trim();
    if (trimmed.length === 0) return;
    if (trimmed[0] !== '{') {
      // The launcher writes a banner on stdout before protocol frames.
      if (!trimmed.startsWith('[ctrl-immune]')) {
        chat.lineCount += 1;
      }
      return;
    }

    let frame;
    try {
      frame = JSON.parse(trimmed);
    } catch {
      return;
    }

    // A response settles its pending request.
    if (frame.id !== undefined && frame.method === undefined) {
      const slot = chat.pending.get(frame.id);
      if (!slot) return;
      chat.pending.delete(frame.id);
      clearTimeout(slot.timer);
      if (frame.error) {
        slot.reject(new Error(`${slot.method} 失败：${frame.error.message ?? frame.error.code}`));
      } else {
        slot.resolve(frame.result);
      }
      return;
    }

    if (frame.method === 'session.event') {
      this.handleSessionEvent(chat, frame.params);
      return;
    }
    if (frame.method === 'session.status') {
      const status = frame.params?.status;
      if (status === 'idle' && chat.status === 'running') {
        chat.status = 'idle';
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'idle' });
      } else if (status === 'running' && chat.status !== 'running') {
        chat.status = 'running';
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
      }
      return;
    }
    // subagent.started / subagent.finished and anything else the runtime adds.
    if (typeof frame.method === 'string') {
      const record = chat.push({
        kind: 'engine_note',
        role: 'engine',
        text: frame.method,
        meta: frame.params ?? null,
      });
      this.emitEvent(chat, record);
    }
  }

  handleStderr(chat, line) {
    const trimmed = line.trim();
    if (trimmed.length === 0) return;
    if (trimmed.startsWith('[ctrl-immune]')) return; // launcher banner
    // The runtime reports operational failures here; keep them visible but
    // never as conversation content.
    const record = chat.push({ kind: 'engine_log', role: 'engine', text: trimmed.slice(0, 1000) });
    this.emitEvent(chat, record);
  }

  /**
   * A streamed `assistant/chunk` needs per-chat aggregation state: deltas
   * arrive one token group at a time and must be coalesced into one visible
   * message. Everything else reuses the shared DSH normaliser, so live and
   * stored views cannot drift apart.
   */
  handleSessionEvent(chat, params) {
    const ev = params?.event;
    if (!ev) return;

    if (process.env.TERMDESK_CHAT_DEBUG === '1') {
      const summary = ev.type === 'assistant/chunk'
        ? `${ev.type} ${ev.data?.chunk?.type ?? ''} idx=${ev.data?.chunk?.index ?? ''}`
        : `${ev.type}${ev.data?.source?.kind ? ` source=${ev.data.source.kind}` : ''}`;
      console.error(`[chat ${chat.id}] ${summary}`);
    }

    if (ev.type === 'assistant/chunk') {
      this.handleAssistantChunk(chat, ev);
      return;
    }

    // The runtime echoes the user's own message back as a `user/message` event.
    // That copy is authoritative in content but arrives after the injected
    // context, so keeping it would place the user's words below context blocks
    // and show them twice. When it matches the optimistic line already on
    // screen, the runtime copy is dropped and the original position kept.
    if (ev.type === 'user/message') {
      const normalized = dshEventToChatEvent(ev);
      if (normalized && normalized.role === 'user') {
        const pending = chat.pendingUserEcho;
        if (pending && pending.text.trim() === normalized.text.trim()) {
          chat.pendingUserEcho = null;
          // Tell the client the optimistic line is now confirmed, so it can
          // stop showing it as pending. Nothing is removed: it is the same line.
          pending.optimistic = false;
          this.emitEvent(chat, pending);
          return;
        }
      }
      // Anything else (unexpected text, or injected context) flows through the
      // shared normaliser as usual.
      if (normalized) this.pushAndEmit(chat, normalized);
      return;
    }

    // A completed assistant message is authoritative: the deltas were only a
    // preview of it. Drop the streamed answer previews and keep the final text,
    // so the answer is shown once instead of twice.
    if (ev.type === 'assistant/message') {
      const normalized = dshEventToChatEvent(ev);
      if (ev.data?.usage) chat.usage = ev.data.usage;
      chat.streaming = null;
      if (!normalized) {
        this.discardPreviews(chat, 'message');
        this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
        return;
      }
      if (normalized.kind === 'message') {
        this.discardPreviews(chat, 'message');
      }
      this.pushAndEmit(chat, normalized);
      return;
    }

    if (ev.type === 'turn/end') {
      chat.streaming = null;
      // Any preview still open when the turn ends was never confirmed by a
      // final message; keep it as the record rather than dropping real output.
      for (const preview of chat.previews) preview.streaming = false;
      chat.previews = [];
      const reason = ev.data?.reason?.kind ?? null;
      const record = chat.push({ kind: 'turn', role: 'engine', text: '', meta: { state: 'ended', reason } });
      this.emitEvent(chat, record);
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'ended', reason });
      return;
    }

    const normalized = dshEventToChatEvent(ev);
    if (normalized) this.pushAndEmit(chat, normalized);
  }

  handleAssistantChunk(chat, ev) {
    const chunk = ev.data?.chunk ?? {};
    const index = chunk.index ?? 0;

    if (chunk.type === 'block-start') {
      chat.streaming = { index, blockType: chunk.blockType ?? 'text', text: '' };
      return;
    }
    if (chunk.type === 'block-end') {
      // Close the preview, but keep it registered: the authoritative
      // `assistant/message` may still replace it later in the turn.
      if (chat.streaming) chat.streaming.open = false;
      chat.streaming = null;
      return;
    }

    const deltaTypes = {
      'text-delta': 'text',
      'reasoning-delta': 'reasoning',
    };
    const blockType = deltaTypes[chunk.type];
    if (!blockType) return;

    if (!chat.streaming || chat.streaming.index !== index) {
      chat.streaming = { index, blockType, text: '' };
    }
    chat.streaming.text += chunk.text ?? '';

    const kind = blockType === 'reasoning' ? 'reasoning' : 'message';

    // Coalesce deltas into one growing event instead of one frame per token:
    // the phone renders far better and the wire stays small.
    const openPreview = chat.previews.filter((p) => p.kind === kind).pop();
    const last = chat.events[chat.events.length - 1];
    if (openPreview && last === openPreview) {
      last.text += chunk.text ?? '';
      this.emitEvent(chat, last, { stream: true });
      return;
    }

    const record = chat.push({ kind, role: 'assistant', text: chunk.text ?? '', streaming: true });
    chat.previews.push(record);
    this.emitEvent(chat, record, { stream: true });
  }

  pushAndEmit(chat, normalized) {
    const record = chat.push(normalized);
    this.emitEvent(chat, record);
    return record;
  }

  /**
   * Publish one transcript change.
   *
   * The frame carries the whole record, not just its seq: the phone renders a
   * live answer token by token, and a frame that only said "something changed"
   * would force a `chat.read` round trip per delta.
   */
  emitEvent(chat, record, extra = {}) {
    this.emit({ event: 'chat.event', chatId: chat.id, seq: record.seq, item: record, ...extra });
  }

  /**
   * Remove streamed preview events of one kind from the transcript.
   *
   * The runtime emits `assistant/chunk` deltas for live display and then one
   * `assistant/message` holding the finished blocks. Only the finished message
   * is durable, so the previews of that same kind are dropped to keep the
   * transcript equal to what the runtime actually recorded.
   *
   * @returns {number} how many preview events were removed.
   */
  discardPreviews(chat, kind) {
    const doomed = new Set(chat.previews.filter((p) => p.kind === kind));
    if (doomed.size === 0) return 0;
    chat.events = chat.events.filter((e) => !doomed.has(e));
    chat.previews = chat.previews.filter((p) => !doomed.has(p));
    // The client learned about those seqs already; tell it they are gone so its
    // incremental view cannot keep the duplicate.
    for (const preview of doomed) {
      this.emit({ event: 'chat.event', chatId: chat.id, seq: preview.seq, removed: true });
    }    return doomed.size;
  }

  failPending(chat, error) {
    for (const [id, slot] of [...chat.pending]) {
      chat.pending.delete(id);
      clearTimeout(slot.timer);
      slot.reject(error);
    }
  }

  /** Terminate a chat's runtime/turn and cancel its in-flight request. */
  dispose(chat, _reason) {
    this.failPending(chat, new Error('会话已结束'));
    if (chat.engine === 'codex') {
      // The kernel owns the session now: stop the in-flight turn and keep the
      // thread resumable. Nothing to kill locally.
      this.clearCodexWatchdog(chat);
      if (chat.threadId) {
        this.codexThreads.delete(chat.threadId);
        if (chat.status === 'running') this.codex?.interruptTurn(chat.threadId).catch(() => {});
      }
      chat.ready = false;
      return;
    }
    const child = chat.child;
    chat.child = null;
    chat.ready = false;
    if (!child || child.exitCode !== null) return;

    try {
      // Ask politely first: the runtime disposes its root context on `shutdown`.
      const id = String(chat.nextRpcId++);
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method: 'shutdown' })}\n`);
    } catch {
      // stdin already gone; fall through to the hard kill.
    }
    const pid = child.pid;
    setTimeout(() => {
      if (child.exitCode !== null) return;
      try {
        if (process.platform === 'win32' && pid) {
          spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
        } else {
          child.kill('SIGKILL');
        }
      } catch {
        try { child.kill(); } catch { /* already gone */ }
      }
    }, 1500).unref?.();
  }
}

/** Attach the per-chat JSON-RPC request helper. Kept out of the class body for readability. */
Chat.prototype.request = function request(method, params, timeoutMs) {
  const chat = this;
  const child = chat.child;
  if (!child || child.exitCode !== null) {
    return Promise.reject(new Error('运行时未运行'));
  }
  const id = String(chat.nextRpcId++);
  const frame = { jsonrpc: '2.0', id, method };
  if (params !== undefined) frame.params = params;

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      chat.pending.delete(id);
      reject(new Error(`${method} 超时`));
    }, timeoutMs);
    chat.pending.set(id, { resolve, reject, timer, method });
    try {
      child.stdin.write(`${JSON.stringify(frame)}\n`);
    } catch (err) {
      chat.pending.delete(id);
      clearTimeout(timer);
      reject(err);
    }
  });
};

export const CHAT_DEFAULTS = { MAX_CHATS, MAX_PROMPT_CHARS, IDLE_TTL_MS };
export { findDsh as findChatDsh };
