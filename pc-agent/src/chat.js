/**
 * Live chat sessions over the DSH SDK runtime.
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
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';

import { dshEventToChatEvent } from './sessions.js';

const MAX_CHATS = 8;
const MAX_EVENTS_PER_CHAT = 1200;
const MAX_PROMPT_CHARS = 24000;
const IDLE_TTL_MS = 45 * 60 * 1000;
const INIT_TIMEOUT_MS = 120 * 1000;
const PROMPT_TIMEOUT_MS = 30 * 60 * 1000;
const REAP_INTERVAL_MS = 60 * 1000;

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

/** One live conversation: one DSH SDK runtime process plus its transcript. */
class Chat {
  constructor({ id, title, cwd, provider, model }) {
    this.id = id;
    this.title = title;
    this.cwd = cwd;
    this.provider = provider;
    this.model = model;
    this.createdAt = Date.now();
    this.lastUsedAt = Date.now();
    /** running | idle | stopped | failed */
    this.status = 'idle';
    /** True once initialize succeeded and the runtime can accept prompts. */
    this.ready = false;
    /** Durable wire session id; also the runtime's own identity. */
    this.sessionId = `termdesk-${id}-${Date.now().toString(36)}`;
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
     * the user is not mistaken for the echo of this one.
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
      provider: this.provider,
      model: this.model,
      status: this.status,
      ready: this.ready,
      sessionId: this.sessionId,
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

  create({ cwd, provider, model, title }) {
    this.dropIdleChat();
    chatCounter += 1;
    const id = `c${chatCounter}`;
    const route = defaultRoute();
    const workdir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
    const chat = new Chat({
      id,
      title: (title && String(title).trim()) || '新对话',
      cwd: workdir,
      provider: provider || route.provider,
      model: model || route.model,
    });
    this.chats.set(id, chat);
    chat.push({ kind: 'local', role: 'engine', text: `会话已创建 · ${chat.cwd}` });
    chat.push({
      kind: 'local',
      role: 'engine',
      text: `模型 ${chat.provider} / ${chat.model}`,
    });
    return chat.summary();
  }

  /**
   * Send one user message and start the turn.
   *
   * Resolves as soon as the runtime accepts the prompt (a durable enqueue
   * receipt), not when the answer is done: the answer arrives as streamed
   * `chat.event` frames over the same socket the request came in on.
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

    // Show the user's own message immediately, before the runtime answers.
    // It is marked optimistic because the runtime echoes the same message back
    // as a `user/message` event a moment later; without reconciling the two the
    // user's own words would appear twice in the transcript.
    const userSeq = chat.push({ kind: 'message', role: 'user', text: message, optimistic: true });
    chat.pendingUserEcho = userSeq;
    if (chat.titleSource === 'prompt') {
      const firstLine = message.split(/\r?\n/)[0].slice(0, 60);
      if (firstLine.trim()) {
        chat.title = firstLine.trim();
        chat.titleSource = 'user';
      }
    }

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

    return { ok: true, userSeq, messageId: receipt?.messageId ?? null, sessionId: chat.sessionId };
  }

  /** Stop a chat: there is no wire cancel, so this disposes the runtime. */
  cancel(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.status !== 'running') return { ok: false, code: 'not_running', message: '当前没有进行中的回复' };
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

  /** Terminate a chat's runtime and cancel its in-flight request. */
  dispose(chat, _reason) {
    this.failPending(chat, new Error('会话已结束'));
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
