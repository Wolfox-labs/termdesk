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
import { AcpKernel, acpUpdateToChatEvents, acpSessionToSessionInfo } from './kernels/acp.js';
import { decisionFor } from './kernels/codex.js';
import { CliKernel } from './kernels/cli.js';
import { ensureOverlay, routeConfig, runtimeArgs } from './kernels/dsh.js';
import { chatEngineIds, isAcpKernel, isCliKernel, kernelTier, shimSpec, spawnSpec } from './kernels/registry.js';
import { ApprovalBroker, OPTIONS, describe as describeApproval } from './approvals.js';
import { dshEventToChatEvent } from './sessions.js';

/** The only option ids an engine's answer may be translated into. */
const KNOWN_OPTIONS = [OPTIONS.ALLOW_ONCE, OPTIONS.ALLOW_ALWAYS, OPTIONS.DENY];

/** One line describing what Codex is asking for. */
function describeCodexRequest(params) {
  const command = params?.command ?? params?.commandLine?.[0] ?? null;
  if (typeof command === 'string' && command.trim()) {
    const args = Array.isArray(params?.commandLine) ? params.commandLine.slice(1).join(' ') : '';
    return args ? `${command} ${args}` : command;
  }
  const changes = params?.changes ?? params?.fileChanges ?? null;
  if (Array.isArray(changes) && changes.length > 0) {
    return changes.map((c) => c?.path ?? c?.file ?? '').filter(Boolean).join(', ');
  }
  if (typeof params?.path === 'string') return params.path;
  return '（内核没有说明具体内容）';
}

const MAX_CHATS = 8;
const MAX_EVENTS_PER_CHAT = 1200;
const MAX_PROMPT_CHARS = 24000;
/**
 * How much of one command's output the phone's copy keeps.
 *
 * The screen shows a few hundred lines; the cap is here so that a command which
 * prints forever cannot grow this process's memory through the transcript copy.
 * The tail is what is kept — that is where the answer usually is.
 */
const MAX_TERMINAL_OUTPUT_CHARS = 64 * 1024;
const IDLE_TTL_MS = 45 * 60 * 1000;
const INIT_TIMEOUT_MS = 120 * 1000;
const PROMPT_TIMEOUT_MS = 30 * 60 * 1000;
const REAP_INTERVAL_MS = 60 * 1000;

/**
 * Engines a chat conversation can run on.
 *
 * No longer a hard-coded pair: the kernel registry decides, so a kernel becomes
 * reachable from the phone the moment its tier allows it — 'native' (an adapter
 * written for one product) or 'acp' (the shared Agent Client Protocol adapter).
 * A kernel that is installed but whose tier is 'shim' or 'unsupported' is
 * deliberately absent here; the picker explains why instead of offering it.
 */
export const CHAT_ENGINES = chatEngineIds();

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
  const route = routeConfig();
  return { provider: route.provider, model: route.model };
}

/** The name a kernel gave a mode, so the transcript says "Bypass Permissions" and not an id. */
function modeLabel(chat, modeId) {
  const modes = chat.availableModes?.availableModes ?? [];
  return modes.find((m) => m.id === modeId)?.name ?? modeId;
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
  constructor({ id, title, cwd, engine, provider, model, effort = null, threadId = null, nativeSessionId = null }) {
    this.id = id;
    this.title = title;
    this.cwd = cwd;
    /** 'codex' | 'dsh' — which engine backs this conversation. */
    this.engine = engine;
    this.provider = provider;
    this.model = model;
    /**
     * Reasoning effort (low / high / ...), engine specific. Null means the
     * kernel default. The app-server documents effort as applying to the turn
     * and subsequent turns, so this mirrors what the thread is actually using.
     */
    this.effort = effort;
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
    /** ACP: { current, models[] } as the kernel described the session. */
    this.availableModels = null;
    /** ACP: the model already pushed to the kernel, so it is pushed once. */
    this.appliedModel = null;
    /** ACP: the session's permission / agent mode, as the kernel named it. */
    this.mode = null;
    /** ACP: the modes this session declares (so the phone never guesses one). */
    this.availableModes = null;
    /**
     * True while this chat's ACP session is backed by a live kernel process.
     * ACP chats hold no child of their own (one process serves every session on
     * that kernel), so liveness has to be recorded explicitly.
     */
    this.kernelLive = false;
    this.events = [];
    this.seq = 0;
    /**
     * The command lines this conversation ran, keyed by the kernel's own id.
     *
     * Kept per conversation, not globally: "which terminal belongs to what I am
     * doing right now" is the question the phone asks, and a session's terminals
     * must not be mixed with another session's on the same kernel process.
     */
    this.terminals = new Map();
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

  /**
   * Remember a command line this conversation is running.
   *
   * `origin` is the part that matters to the phone: `agent` means the kernel ran
   * the command inside its own process (Codex) — we can list it, watch it and
   * stop it, but the protocol has no way to type into it; `kernel` means the
   * kernel asked this agent to run it (ACP) — that process is ours, so the
   * phone can drive it. Saying which is which beats offering a dead input box.
   */
  startTerminal({ id, origin, command, cwd = null, processId = null }) {
    const record = {
      id,
      origin,
      command,
      cwd,
      processId,
      state: 'running',
      exitCode: null,
      startedAt: Date.now(),
      finishedAt: null,
      output: '',
      bytes: 0,
      truncated: false,
    };
    this.terminals.set(id, record);
    return record;
  }

  appendTerminal(id, chunk) {
    const record = this.terminals.get(id);
    if (!record || typeof chunk !== 'string' || !chunk) return record ?? null;
    record.output += chunk;
    record.bytes += Buffer.byteLength(chunk, 'utf8');
    if (record.output.length > MAX_TERMINAL_OUTPUT_CHARS) {
      record.output = record.output.slice(-MAX_TERMINAL_OUTPUT_CHARS);
      record.truncated = true;
    }
    return record;
  }

  finishTerminal(id, { exitCode = null } = {}) {
    const record = this.terminals.get(id);
    if (!record) return null;
    if (record.state !== 'exited') {
      record.state = 'exited';
      record.exitCode = exitCode;
      record.finishedAt = Date.now();
    }
    return record;
  }

  /** The phone's view: no output bodies, just what exists and what it can do. */
  terminalList() {
    return [...this.terminals.values()]
      .sort((a, b) => a.startedAt - b.startedAt)
      .map((t) => ({
        id: t.id,
        origin: t.origin,
        command: t.command,
        cwd: t.cwd,
        state: t.state,
        exitCode: t.exitCode,
        startedAt: t.startedAt,
        finishedAt: t.finishedAt,
        bytes: t.bytes,
        truncated: t.truncated,
        canWrite: t.origin === 'kernel' && t.state === 'running',
      }));
  }

  terminalDetail(id) {
    const t = this.terminals.get(id);
    if (!t) return null;
    return {
      id: t.id,
      origin: t.origin,
      command: t.command,
      cwd: t.cwd,
      state: t.state,
      exitCode: t.exitCode,
      output: t.output,
      truncated: t.truncated,
      canWrite: t.origin === 'kernel' && t.state === 'running',
    };
  }

  summary() {
    return {
      id: this.id,
      title: this.title,
      cwd: this.cwd,
      engine: this.engine,
      provider: this.provider,
      model: this.model,
      effort: this.effort,
      status: this.status,
      ready: this.ready,
      sessionId: this.sessionId,
      threadId: this.threadId,
      mode: this.mode,
      createdAt: this.createdAt,
      lastUsedAt: this.lastUsedAt,
      eventCount: this.events.length,
      lastError: this.lastError,
      usage: this.usage,
      live: Boolean((this.child && this.child.exitCode === null) || this.kernelLive),
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
    /**
     * One ACP agent process per kernel id, shared by every chat on it.
     *
     * ACP servers are multi-session, so one process serves the whole list and
     * routing happens on the session id. That is what keeps the process count
     * flat (a phone with five OpenCode conversations still has one child).
     */
    this.acp = new Map();
    /** acp session id -> Chat, for routing session/update notifications. */
    this.acpSessions = new Map();
    /**
     * Every "may I run this?" question from any engine goes through here, so the
     * phone answers in one place and every decision lands in the transcript.
     */
    this.approvals = new ApprovalBroker();
    this.approvals.on('settled', (info) => this.noteApproval(info));
    /**
     * Conversations whose terminals the client is currently watching.
     *
     * Output is only forwarded while somebody is looking: a build printing
     * thousands of lines must not be pushed at a phone that is on another
     * screen. The list itself is always kept, so opening the panel shows the
     * history either way.
     */
    this.terminalWatch = new Set();
  }

  /** Route chat events to the currently connected client. */
  attach(onEvent) {
    this.onEvent = onEvent;
    // A new client has not opened any terminal panel yet; assuming otherwise
    // would push a build log at a socket that never asked for it.
    if (this.onEvent !== onEvent) this.terminalWatch.clear();
    // The broker sniffs for a client: with nobody attached it settles requests
    // immediately instead of waiting for a phone that may never come back.
    this.approvals.attach(onEvent);
  }

  /** Stop routing to a socket that went away; live chats survive the disconnect. */
  detach() {
    this.onEvent = null;
    this.approvals.detach();
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

  create({ cwd, provider, model, title, engine, effort }) {
    const eng = engine == null || engine === '' ? 'dsh' : String(engine);
    if (!CHAT_ENGINES.includes(eng)) {
      const tier = kernelTier(eng);
      return {
        ok: false,
        code: 'bad_engine',
        message: tier === 'shim'
          ? `"${eng}" 的 CLI shim 适配器已就绪，但 manifest 还没经过一次真实实测，暂不开放`
          : tier === 'unsupported'
            ? `"${eng}" 没有可编程接口，无法接入`
            : `不支持的引擎 "${engine}"（可用：${CHAT_ENGINES.join(' / ')}）`,
      };
    }
    this.dropIdleChat();
    chatCounter += 1;
    const id = `c-${crypto.randomUUID()}`;
    // ACP kernels keep their own default model, which may be an expensive one.
    // TERMDESK_ACP_MODEL pins it (a cost decision belongs to the machine's owner),
    // and the phone's picker overrides it per conversation.
    const route = eng === 'dsh'
      ? defaultRoute()
      : isAcpKernel(eng) || isCliKernel(eng)
        ? { provider: null, model: process.env.TERMDESK_ACP_MODEL || null }
        : { provider: null, model: null };
    const workdir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
    const chat = new Chat({
      id,
      title: (title && String(title).trim()) || '新对话',
      cwd: workdir,
      engine: eng,
      provider: provider || route.provider,
      model: model || route.model,
      effort: effort || null,
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
    // ACP kernels own their session store, so "open history" is the same
    // operation as "continue": the kernel replays it and the next message
    // continues it. Nothing is reconstructed from display text.
    if (isAcpKernel(engine) || isCliKernel(engine)) return this.resumeAcp(engine, id);
    // Every resumable engine now goes through its own adapter; there is no
    // generic fallback that reconstructs a session out of display text.
    return { ok: false, code: 'resume_unsupported', message: `${engine} 内核未提供经过验证的恢复接口` };
  }
  /**
   * Change what the next turns of a live conversation will use.
   *
   * Model and reasoning effort are per-thread sticky settings in the kernel, so
   * they are real state, not a UI hint: the change is recorded on the chat, the
   * user sees a line in the transcript, and the next turn carries it.
   */
  async setConfig(id, { model, effort, title, mode } = {}) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (model !== undefined) chat.model = model == null || model === '' ? null : String(model);
    if (effort !== undefined) chat.effort = effort == null || effort === '' ? null : String(effort);
    if (title !== undefined && title != null && String(title).trim()) {
      chat.title = String(title).trim();
      chat.titleSource = 'user';
    }
    // A permission / agent mode is not sticky state on our side: the kernel owns
    // it and it applies to the live session, so it has to be pushed now. A
    // kernel without modes says so instead of accepting a switch that does
    // nothing.
    if (mode !== undefined && mode !== null && String(mode).trim() !== '') {
      if (!isAcpKernel(chat.engine)) {
        return { ok: false, code: 'mode_unsupported', message: `${chat.engine} 内核没有权限模式开关` };
      }
      try {
        const kernel = await this.ensureAcpSession(chat);
        if (!kernel.setMode) {
          return { ok: false, code: 'mode_unsupported', message: `${chat.engine} 内核不支持切换权限模式` };
        }
        await kernel.setMode(chat.sessionId, String(mode));
        chat.mode = String(mode);
      } catch (err) {
        return { ok: false, code: 'mode_failed', message: `切换权限模式失败：${String(err?.message ?? err)}` };
      }
    }
    chat.lastUsedAt = Date.now();
    chat.push({
      kind: 'local',
      role: 'engine',
      text: `已切换 · 模型 ${chat.model ?? '内核默认'} · 思考 ${chat.effort ?? '默认'}`
        + (chat.mode ? ` · 权限 ${modeLabel(chat, chat.mode)}` : ''),
    });
    return { ok: true, chat: chat.summary() };
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
  async send(id, text, opts = {}) {
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

    // Per-turn overrides: the kernel documents model and effort as applying to
    // this turn and subsequent turns, so the chat adopts them and stays truthful
    // about what it is actually using.
    if (typeof opts.model === 'string' && opts.model.trim()) chat.model = opts.model.trim();
    if (typeof opts.effort === 'string' && opts.effort.trim()) chat.effort = opts.effort.trim();

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

    if (chat.engine === 'codex') return this.sendCodex(chat, message, userSeq);
    if (isAcpKernel(chat.engine) || isCliKernel(chat.engine)) return this.sendAcp(chat, message, userSeq);
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
      const turn = await server.startTurn(chat.threadId, message, {
        ...(chat.model ? { model: chat.model } : {}),
        ...(chat.effort ? { effort: chat.effort } : {}),
      });
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
      // Codex's own approval requests become questions on the phone.
      server.approvalHandler = (method, params) => this.answerCodexApproval(method, params);
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

  // --- the conversation's command lines ------------------------------------

  /**
   * What this conversation has been running.
   *
   * Asking for the list is what tells the agent somebody is watching, so live
   * output starts flowing; without it a build log would be pushed at a phone
   * that is looking at another screen.
   */
  async chatTerminals(chatId) {
    const chat = this.chats.get(chatId);
    if (!chat) return null;
    this.terminalWatch.add(chatId);
    // Codex keeps the background terminals of a thread on its own side, so the
    // kernel is asked. That is what makes the list "what is still running on the
    // PC" instead of "what happened while this phone happened to be connected".
    if (chat.engine === 'codex' && chat.threadId) {
      await this.refreshCodexTerminals(chat).catch(() => {});
    }
    return { chatId, terminals: chat.terminalList() };
  }

  chatTerminalRead(chatId, terminalId) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminalDetail(terminalId);
    if (!chat || !record) return null;
    this.terminalWatch.add(chatId);
    // `terminalId` on the wire, `id` in the record: the field the client sent is
    // the field it gets back, so a frame can never be matched against the wrong
    // key (this was silently mismatched once already).
    return { chatId, terminalId: record.id, ...record };
  }

  async chatTerminalWrite(chatId, terminalId, data) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminals.get(terminalId);
    if (!chat || !record) throw new Error('没有这个终端');
    if (record.origin !== 'kernel') {
      // The refusal has to be specific: "the kernel runs this one itself, and
      // the protocol has no write channel for it" is something a user can act on.
      throw new Error('这条命令由内核自己执行，协议没有提供写入通道；只能看输出或终止它');
    }
    const kernel = this.kernelFor(chat.engine);
    const ok = kernel.terminals?.write?.(terminalId, String(data ?? ''));
    if (!ok) throw new Error('终端已经结束了');
    return { chatId, terminalId, wrote: true };
  }

  async chatTerminalStop(chatId, terminalId) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminals.get(terminalId);
    if (!chat || !record) throw new Error('没有这个终端');
    if (record.state !== 'running') return { chatId, terminalId, stopped: false, reason: '已经结束' };
    if (record.origin === 'kernel') {
      this.kernelFor(chat.engine).terminals?.kill({ terminalId });
      return { chatId, terminalId, stopped: true };
    }
    if (!chat.threadId || !record.processId) throw new Error('这条命令不在可终止的后台终端清单里');
    await this.codexServer().call('thread/backgroundTerminals/terminate', {
      threadId: chat.threadId,
      processId: record.processId,
    });
    chat.finishTerminal(terminalId, { exitCode: null });
    this.emitTerminalList(chat);
    return { chatId, terminalId, stopped: true };
  }

  /** Merge Codex's own list of a thread's background terminals into the record. */
  async refreshCodexTerminals(chat) {
    const result = await this.codexServer().call('thread/backgroundTerminals/list', { threadId: chat.threadId });
    const data = Array.isArray(result?.data) ? result.data : [];
    let changed = false;
    for (const entry of data) {
      const id = entry?.itemId ?? entry?.processId;
      if (!id) continue;
      const existing = chat.terminals.get(id);
      if (existing) {
        if (!existing.processId && entry.processId) existing.processId = entry.processId;
        continue;
      }
      chat.startTerminal({
        id,
        origin: 'agent',
        command: entry.command ?? '',
        cwd: entry.cwd ?? chat.cwd ?? null,
        processId: entry.processId ?? null,
      });
      changed = true;
    }
    if (changed || data.length > 0) this.emitTerminalList(chat);
    return data.length;
  }

  /**
   * ACP terminal events: the kernel asked us to run something, or that process
   * produced output / exited / was released.
   */
  handleAcpTerminal(engineId, event) {
    const sessionId = event?.sessionId ?? event?.terminal?.sessionId ?? null;
    const chat = sessionId ? this.acpSessions.get(sessionId) : null;
    if (!chat) return;
    if (event.type === 'created' && event.terminal?.id) {
      chat.startTerminal({
        id: event.terminal.id,
        origin: 'kernel',
        command: event.terminal.command ?? '',
        cwd: event.terminal.cwd ?? chat.cwd ?? null,
      });
      this.emitTerminalList(chat);
      return;
    }
    const terminalId = event.terminalId ?? event.terminal?.id;
    if (!terminalId) return;
    if (event.type === 'output') {
      chat.appendTerminal(terminalId, event.chunk ?? '');
      if (this.terminalWatch.has(chat.id)) {
        this.emit({ event: 'chat.terminal.output', chatId: chat.id, terminalId, chunk: event.chunk ?? '' });
      }
      return;
    }
    if (event.type === 'input' && this.terminalWatch.has(chat.id)) {
      this.emit({ event: 'chat.terminal.input', chatId: chat.id, terminalId, data: event.data ?? '' });
      return;
    }
    if (event.type === 'exited') {
      chat.finishTerminal(terminalId, { exitCode: event.exitCode ?? null });
      this.emitTerminalList(chat);
      return;
    }
    if (event.type === 'released') {
      chat.terminals.delete(terminalId);
      this.emitTerminalList(chat);
    }
  }

  emitTerminalList(chat) {
    this.emit({ event: 'chat.terminals', chatId: chat.id, terminals: chat.terminalList() });
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
      effort: thread.reasoningEffort ?? null,
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

  // --- approvals -----------------------------------------------------------

  /**
   * One Codex approval request -> one question on the phone.
   *
   * The reason is taken from the request itself (the command, or the patch), so
   * the phone shows what will actually run rather than "the agent wants
   * permission".
   */
  async answerCodexApproval(method, params) {
    const chat = this.findChatByThread(params?.threadId) ?? this.newestCodexChat();
    const detail = describeCodexRequest(params);
    const optionId = await this.approvals.request({
      chatId: chat?.id ?? null,
      engine: 'codex',
      title: 'Codex 想要执行',
      detail,
      kind: /patch|filechange|file/i.test(method) ? 'file' : 'command',
      fallback: OPTIONS.DENY,
    }).catch(() => OPTIONS.DENY);
    return decisionFor(optionId);
  }

  /**
   * One ACP permission request -> one question on the phone.
   *
   * The kernel's own options are reused as the answer vocabulary when they match
   * ours, so "always allow" means what the kernel means by it.
   */
  async handleAcpPermission(engineId, info) {
    const chat = info.sessionId ? this.acpSessions.get(info.sessionId) : null;
    const options = Array.isArray(info.options) ? info.options : [];
    const ids = options
      .map((o) => o?.optionId ?? o?.id)
      .filter((id) => KNOWN_OPTIONS.includes(id));
    // A kernel that offers something we do not speak (a "cancel" kind, say)
    // still gets an answer: the vocabulary is ours, and anything unmapped is
    // simply not offered.
    const optionId = await this.approvals.request({
      chatId: chat?.id ?? null,
      engine: engineId,
      title: info.toolCall?.title ? `内核想要执行 ${info.toolCall.title}` : '内核请求权限',
      // The kind rides along as its own field, so repeating it here would only
      // make the sentence longer without saying anything new.
      detail: info.toolCall?.title ? '' : (info.toolCall?.kind ?? ''),
      kind: info.toolCall?.kind === 'edit' || info.toolCall?.kind === 'write' ? 'file' : 'tool',
      ids: ids.length > 0 ? ids : null,
      fallback: KNOWN_OPTIONS.includes(info.defaultOptionId) ? info.defaultOptionId : OPTIONS.DENY,
    }).catch(() => OPTIONS.DENY);
    info.respond(optionId === OPTIONS.DENY ? null : optionId);
  }

  /**
   * The phone's answer.
   *
   * Rejected rather than trusted when the request is unknown or already settled:
   * an answer that is not currently pending must not be able to decide
   * something else.
   */
  resolveApproval({ requestId, optionId }) {
    if (!KNOWN_OPTIONS.includes(optionId)) {
      return { ok: false, code: 'bad_option', message: `不认识的选项：${optionId}` };
    }
    return this.approvals.resolve({ requestId, optionId });
  }

  /** Write the decision into the conversation it belongs to. */
  noteApproval({ request, optionId, by, note }) {
    if (!note) return;
    const chat = request.chatId ? this.chats.get(request.chatId) : null;
    if (!chat) return;
    this.pushAndEmit(chat, { kind: 'engine_note', role: 'engine', text: note, name: 'permission' });
  }

  findChatByThread(threadId) {
    if (!threadId) return null;
    return this.codexThreads.get(threadId) ?? null;
  }

  /** Fallback for a Codex request that does not name its thread. */
  newestCodexChat() {
    const list = [...this.chats.values()]
      .filter((c) => c.engine === 'codex')
      .sort((a, b) => b.lastUsedAt - a.lastUsedAt);
    return list[0] ?? null;
  }

  /** Route one app-server notification into the right chat transcript. */
  handleCodexNotification(method, params) {
    const threadId = params?.threadId ?? null;
    const chat = threadId ? this.codexThreads.get(threadId) : null;

    if (method === 'turn/started') { if (chat) chat.currentTurnId = params?.turn?.id ?? null; return; }
    if (method === 'turn/completed') { if (chat) this.finishCodexTurn(chat, params?.turn ?? {}); return; }
    if (!chat || chat.status === 'stopped') return;

    // The command lines this conversation runs. Codex executes them inside its
    // own process, so the honest offer is: listed, watchable while it runs,
    // stoppable — but not typeable. `process/writeStdin` and `command/exec/write`
    // both require a process this client created, and this one is not.
    if (method === 'item/started' && params?.item?.type === 'commandExecution') {
      const item = params.item;
      if (item.id) {
        chat.startTerminal({
          id: item.id,
          origin: 'agent',
          command: item.command ?? '',
          cwd: item.cwd ?? chat.cwd ?? null,
        });
        this.emitTerminalList(chat);
      }
    }
    if (method === 'item/commandExecution/outputDelta') {
      const id = params?.itemId;
      if (id && chat.terminals.has(id)) {
        const delta = typeof params?.delta === 'string' ? params.delta : '';
        chat.appendTerminal(id, delta);
        if (delta && this.terminalWatch.has(chat.id)) {
          this.emit({ event: 'chat.terminal.output', chatId: chat.id, terminalId: id, chunk: delta });
        }
      }
    }
    if (method === 'item/completed' && params?.item?.type === 'commandExecution') {
      const item = params.item;
      const record = item.id ? chat.terminals.get(item.id) : null;
      if (record) {
        // The completed item carries the whole output; streamed deltas are the
        // same bytes, so the aggregate is only used when nothing streamed.
        const aggregated = String(item.aggregatedOutput ?? item.aggregated_output ?? '');
        if (aggregated && record.bytes === 0) chat.appendTerminal(item.id, aggregated);
        chat.finishTerminal(item.id, { exitCode: item.exitCode ?? item.exit_code ?? null });
        this.emitTerminalList(chat);
      }
    }
    if (method === 'item/commandExecution/terminalInteraction' && params?.itemId) {
      // The agent answered a prompt in its own terminal. Recording it is how the
      // phone can tell "waiting for input" apart from "just quiet".
      const record = chat.terminals.get(params.itemId);
      if (record) record.lastInputAt = Date.now();
    }

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
        // Deltas are a PREVIEW of the message the kernel sends when the block
        // finishes (item/completed), so each one replaces the last and the whole
        // set is dropped once the authoritative item arrives. ACP works the other
        // way round — see handleAcpUpdate — and using this rule there deleted the
        // entire answer.
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

  // --- ACP plumbing (engine tier 'acp') ------------------------------------

  /**
   * The shared ACP process for a kernel, started on first use.
   *
   * Notifications are routed by session id, and permissions are answered inside
   * the adapter (an unanswered permission request blocks the turn forever). A
   * permission decision is written into the transcript so the user can always
   * see what the kernel was allowed to do on their machine.
   */
  kernelFor(engineId) {
    let kernel = this.acp.get(engineId);
    if (kernel) return kernel;
    const spec = spawnSpec(engineId);
    if (!spec) throw new Error(`找不到 ACP 内核 "${engineId}" 的可执行文件`);
    if (isCliKernel(engineId)) {
      // A CLI-shaped kernel: one adapter, driven by its manifest. Updates are
      // emitted in the ACP shape, so everything downstream is the same code.
      const manifest = shimSpec(engineId);
      if (!manifest) throw new Error('内核没有 shim manifest');
      // spec.args is the prelude (node + script for a node-hosted CLI).
      const cli = new CliKernel({ id: engineId, bin: spec.bin, preArgs: spec.args, manifest, log: (line) => console.log(line) });
      cli.on('update', (sessionId, update) => this.handleAcpUpdate(engineId, sessionId, update));
      cli.on('exit', () => this.acp.delete(engineId));
      this.acp.set(engineId, cli);
      return cli;
    }
    kernel = new AcpKernel({ id: engineId, bin: spec.bin, args: spec.args, log: (line) => console.log(line) });
    kernel.on('update', (sessionId, update) => this.handleAcpUpdate(engineId, sessionId, update));
    kernel.on('permission', (info) => this.handleAcpPermission(engineId, info));
    // The kernel's command lines are ours (see kernels/acp-terminal.js), so what
    // it runs shows up in the conversation it belongs to.
    kernel.on('terminal', (event) => this.handleAcpTerminal(engineId, event));
    kernel.on('serverRequest', (method) => {
      console.error(`[acp:${engineId}] 引擎请求未实现，已拒绝：${method}`);
    });
    kernel.on('exit', (code) => {
      for (const chat of this.chats.values()) {
        if (chat.engine !== engineId || chat.status !== 'running') continue;
        chat.status = 'failed';
        chat.lastError = `${engineId} 内核进程退出（${code}）`;
        chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      }
      this.acp.delete(engineId);
    });
    this.acp.set(engineId, kernel);
    return kernel;
  }

  /**
   * Open this chat's kernel-side session, once, and remember what that session
   * says it can become.
   *
   * Shared by the turn path and by "which models can this conversation use",
   * because both need the SAME session: asking for the model list must not open
   * a second one, and must not leave an empty one behind in the kernel's own
   * history either - the session opened here is the one the first turn uses.
   */
  async openAcpSession(chat, kernel) {
    const sessionId = await kernel.newSession({ cwd: chat.cwd });
    // A CLI kernel names its session, so this can be empty: the id arrives with
    // the first turn. Routing is only keyed once there is something to key on -
    // an empty map key would catch another chat's updates.
    chat.sessionId = sessionId;
    chat.nativeResume = true;
    chat.ready = true;
    // What this kernel says the conversation can be switched to. The phone
    // offers exactly this list, so a model choice is never a guess.
    chat.availableModels = kernel.availableModels(sessionId);
    const options = kernel.sessionOptions?.(sessionId) ?? null;
    chat.availableModes = options?.modes ?? null;
    chat.mode = options?.modes?.currentModeId ?? null;
    if (sessionId) this.acpSessions.set(sessionId, chat);
    return sessionId;
  }

  /** This chat's kernel, started, with a live session on it. */
  async ensureAcpSession(chat) {
    const kernel = this.kernelFor(chat.engine);
    await kernel.ensureStarted();
    if (!chat.ready || !chat.nativeResume) await this.openAcpSession(chat, kernel);
    return kernel;
  }

  /**
   * What this conversation can be switched to.
   *
   * ACP declares its models when a session exists, and TermDesk keeps chats lazy,
   * so the list is asked for when the phone opens the picker instead of at
   * creation: the session that answers this question is the session the first
   * turn then runs on, so nothing is opened twice and no empty conversation is
   * left in the kernel's history.
   *
   * A kernel with no model list (a CLI shim, DSH) says so instead of answering
   * with an empty list that would look like "no models available".
   */
  async modelsFor(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (!isAcpKernel(chat.engine)) {
      return {
        ok: true,
        chatId: id,
        supported: false,
        current: chat.model ?? null,
        models: [],
        modes: null,
        message: `${chat.engine} 内核不由 ACP 提供模型清单`,
      };
    }
    try {
      const kernel = await this.ensureAcpSession(chat);
      const info = chat.availableModels ?? kernel.availableModels(chat.sessionId);
      chat.availableModels = info;
      const options = kernel.sessionOptions?.(chat.sessionId) ?? null;
      return {
        ok: true,
        chatId: id,
        sessionId: chat.sessionId ?? null,
        supported: true,
        current: info?.current ?? null,
        models: (info?.models ?? []).map((m) => ({ id: m.id, label: m.label ?? m.id })),
        // Declared permission/agent modes ride along: they are part of the same
        // session declaration, and the phone's permission switch needs them.
        modes: options?.modes ?? null,
      };
    } catch (err) {
      return {
        ok: false,
        code: 'session_failed',
        message: `${chat.engine} 无法读取模型清单：${String(err?.message ?? err)}`,
      };
    }
  }

  /** One ACP turn: attach (or open) the session, then prompt. */
  async sendAcp(chat, message, userSeq) {
    let kernel;
    try {
      kernel = this.kernelFor(chat.engine);
      await kernel.ensureStarted();
    } catch (err) {
      return this.failAcp(chat, `无法启动 ${chat.engine} 内核：${String(err?.message ?? err)}`, userSeq, 'spawn_failed');
    }

    if (!chat.ready || !chat.nativeResume) {
      try {
        await this.openAcpSession(chat, kernel);
      } catch (err) {
        return this.failAcp(chat, `${chat.engine} 无法创建会话：${String(err?.message ?? err)}`, userSeq, 'session_failed');
      }
    }

    // A model set on the chat (or sent with this turn) has to reach the kernel;
    // otherwise the picker would be decoration. Once per change, not per turn.
    if (chat.model && chat.appliedModel !== chat.model) {
      try {
        await kernel.setModel(chat.sessionId, chat.model);
        chat.appliedModel = chat.model;
      } catch (err) {
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: `无法切换模型（${chat.model}）：${String(err?.message ?? err)}`,
          name: 'warning',
        });
      }
    }

    chat.status = 'running';
    chat.kernelLive = true;
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    this.emitEvent(chat, userSeq);

    try {
      const { stopReason, sessionId: namedSession } = await kernel.prompt(chat.sessionId, message);
      // A kernel that names its own session (every CLI shim) hands the id back
      // here; adopting it is what makes the NEXT turn a resume instead of a
      // new conversation.
      if (namedSession && namedSession !== chat.sessionId) {
        chat.sessionId = namedSession;
        chat.nativeSessionId = namedSession;
        this.acpSessions.set(namedSession, chat);
      }
      // A kernel can conclude a turn having said nothing at all — OpenCode does
      // exactly that when its provider refuses the call (measured: the free tier
      // is refused from third-party clients, and the ACP层 answers end_turn with
      // zero tokens instead of an error). Silence is indistinguishable from a
      // broken product, so say what happened and quote the kernel's own output.
      const produced = chat.events.some(
        (e) => e.seq > userSeq.seq && (e.kind === 'message' || e.kind === 'tool' || e.kind === 'tool_result' || e.kind === 'reasoning'),
      );
      if (!produced) {
        const tail = String(kernel.stderrTail ?? '').replace(/\s+/g, ' ').trim().slice(-300);
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: tail
            ? `内核这一轮没有返回任何内容（stopReason=${stopReason ?? '未知'}）。内核自己的最后输出：${tail}`
            : `内核这一轮没有返回任何内容（stopReason=${stopReason ?? '未知'}），也没有给出原因。这通常意味着内核内部的模型调用被拒绝或额度受限。`,
          name: 'warning',
        });
      }
      this.finishAcpTurn(chat, stopReason);
      return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
    } catch (err) {
      // A rejected prompt is reported after the streamed partial answer, so the
      // transcript keeps whatever the kernel already produced.
      return this.failAcp(chat, `${chat.engine} 调用失败：${String(err?.message ?? err)}`, userSeq, 'prompt_failed');
    }
  }

  failAcp(chat, message, userSeq, code) {
    chat.status = 'failed';
    chat.lastError = message;
    chat.push({ kind: 'error', role: 'engine', text: message });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    return { ok: false, code, message, userSeq };
  }

  /**
   * Open a past ACP session: the kernel replays its transcript as
   * session/update notifications and the next message continues it. This is
   * why history and live chat are one path — the same session id backs both.
   */
  async resumeAcp(engine, id) {
    const adopted = [...this.chats.values()].find((c) => c.engine === engine && c.sessionId === id);
    if (adopted) return { ok: true, chat: adopted.detail() };

    let kernel;
    try {
      kernel = this.kernelFor(engine);
      await kernel.ensureStarted();
    } catch (err) {
      return { ok: false, code: 'engine_unavailable', message: String(err?.message ?? err) };
    }

    // The working directory belongs to the session, so it is read from the
    // kernel's own index rather than guessed.
    let info = null;
    try {
      const listed = await kernel.listSessions();
      info = listed.sessions.find((s) => (s.sessionId ?? s.id) === id) ?? null;
    } catch { /* a kernel without session/list still allows load */ }

    const cwd = info?.cwd ?? null;
    if (cwd && (!fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory())) {
      return { ok: false, code: 'missing_workspace', message: '原会话的工作目录不存在，不能静默切换到其他目录' };
    }

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: info?.title || (info?.cwd ? path.basename(info.cwd) : '历史对话'),
      cwd: cwd ?? os.homedir(),
      engine,
      provider: null,
      model: null,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    chat.ready = true;
    chat.kernelLive = true;
    if (info?.updatedAt) {
      const at = Date.parse(info.updatedAt);
      if (!Number.isNaN(at)) chat.createdAt = at;
    }
    this.chats.set(chat.id, chat);
    // Routing must be in place BEFORE loadSession: the replay arrives as
    // notifications that precede the response.
    this.acpSessions.set(id, chat);

    try {
      await kernel.loadSession(id, { cwd: chat.cwd });
    } catch (err) {
      this.acpSessions.delete(id);
      this.chats.delete(chat.id);
      return { ok: false, code: 'session_busy', message: `内核无法打开该会话：${String(err?.message ?? err)}` };
    }
    this.discardPreviews(chat, 'message');
    this.discardPreviews(chat, 'reasoning');
    chat.push({
      kind: 'local',
      role: 'engine',
      text: kernel.canReplay === false
        ? '已接上该内核的会话 · 后续消息延续原上下文（该内核不提供历史正文，所以这里没有回放）'
        : '已恢复内核原生会话 · 后续消息延续原上下文',
    });
    return { ok: true, chat: chat.detail() };
  }

  /** Route one session/update notification into the right transcript. */
  handleAcpUpdate(engineId, sessionId, update) {
    // Session-id routing, with one honest fallback: a CLI kernel usually names
    // its session only at the end, so updates that arrive before the id is known
    // belong to the one turn this engine has running.
    const chat = (sessionId ? this.acpSessions.get(sessionId) : null)
      ?? [...this.chats.values()].find((c) => c.engine === engineId && c.status === 'running')
      ?? null;
    if (!chat || chat.engine !== engineId) return;
    if (chat.status === 'stopped') return;

    for (const event of acpUpdateToChatEvents(update)) {
      if (event.kind === 'message' && event.role === 'user') {
        if (update.sessionUpdate === 'user_message_chunk') {
          // Live turn: the phone already shows the optimistic line, so confirm
          // it instead of appending a duplicate. Replay (load): there is no
          // optimistic line, so the replayed chunk IS the record.
          const pending = chat.pendingUserEcho;
          if (pending && pending.text.trim() === event.text.trim()) {
            chat.pendingUserEcho = null;
            pending.optimistic = false;
            this.emitEvent(chat, pending);
          } else if (!pending) {
            this.pushAndEmit(chat, { kind: 'message', role: 'user', text: event.text });
          }
        }
        continue;
      }
      if (event.streaming) {
        // ACP streams the answer as chunks and NEVER sends a finished copy of it,
        // so the chunks ARE the message. Consecutive chunks of the same kind are
        // coalesced into one record (same seq, growing text) and the phone is
        // told to replace it; there is nothing to discard at the end of the turn.
        // Treating them as previews instead deleted the whole answer — the phone
        // showed the tool rows and not one word of text, which is the bug that
        // was reported from the device.
        const last = chat.events[chat.events.length - 1];
        if (last && last.streaming && last.kind === event.kind && last.role === event.role) {
          last.text += event.text;
          this.emitEvent(chat, last, { stream: true });
          continue;
        }
        const record = chat.push(event);
        this.emitEvent(chat, record, { stream: true });
        continue;
      }
      if (event.kind === 'message' || event.kind === 'reasoning') this.discardPreviews(chat, event.kind);
      this.pushAndEmit(chat, event);
    }
  }

  /** Close out an ACP turn exactly once, mirroring the Codex lifecycle. */
  finishAcpTurn(chat, stopReason) {
    if (chat.status === 'stopped') return;
    const cancelled = stopReason === 'cancelled';
    const failed = stopReason === 'refusal';
    // Everything streamed during this turn is finished text now, so the cursor
    // stops. (There are no previews to discard on this path: ACP chunks are the
    // message, not a preview of one.)
    for (const record of chat.events.filter((e) => e.streaming)) {
      record.streaming = false;
      this.emitEvent(chat, record, { stream: false });
    }
    chat.status = failed ? 'failed' : 'idle';
    chat.lastError = failed ? '内核拒绝了本轮请求' : null;
    chat.push({
      kind: 'turn', role: 'engine', text: '',
      meta: { state: failed ? 'failed' : 'ended', reason: cancelled ? 'interrupted' : (stopReason ?? null) },
    });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: failed ? 'failed' : 'ended' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
  }

  /**
   * The kernel's own session index for one ACP engine, in the phone's
   * SessionInfo shape. History and live chat share the identity, so an entry
   * listed here is exactly one that resumeAcp can open.
   */
  async listAcpSessions(engineId, { cwd } = {}) {
    const kernel = this.kernelFor(engineId);
    await kernel.ensureStarted();
    const { supported, sessions } = await kernel.listSessions({ cwd });
    if (!supported) return [];
    return sessions.map((s) => acpSessionToSessionInfo(engineId, s)).filter(Boolean);
  }

  /**
   * Read one ACP session's transcript WITHOUT attaching a chat to it.
   *
   * History viewing and history opening are different intents: the phone shows
   * a transcript read-only until the user chooses to continue it. Replaying the
   * kernel's own transcript is the only way to show the truth, and the events
   * are folded here exactly as they are folded live (chunks merge into the
   * message they build), so a replayed answer looks like the original one.
   */
  async readAcpSession(engineId, id) {
    const kernel = this.kernelFor(engineId);
    await kernel.ensureStarted();

    let info = null;
    try {
      const listed = await kernel.listSessions();
      info = listed.sessions.find((s) => (s.sessionId ?? s.id) === id) ?? null;
    } catch { /* a kernel without session/list can still load */ }

    const events = [];
    const push = (event) => {
      events.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: new Date().toISOString(),
        name: event.name ?? null,
        state: event.meta?.state ?? null,
        exitCode: event.meta?.exitCode ?? null,
        tokens: null,
      });
    };
    const listener = (sessionId, update) => {
      if (sessionId !== id) return;
      for (const event of acpUpdateToChatEvents(update)) {
        // A replayed transcript is authoritative, so streamed chunks are folded
        // into the message they are building instead of being kept as previews.
        const last = events[events.length - 1];
        if (event.streaming && last && last.kind === event.kind && last.role === event.role) {
          last.text += event.text;
          continue;
        }
        push(event);
      }
    };

    kernel.on('update', listener);
    try {
      await kernel.loadSession(id, { cwd: info?.cwd });
    } finally {
      kernel.off('update', listener);
    }

    return {
      meta: {
        engine: engineId,
        id,
        cwd: info?.cwd ?? null,
        createdAt: info?.createdAt ?? null,
        title: info?.title ?? null,
        filePath: null,
      },
      events,
      totalEvents: events.length,
      truncated: false,
    };
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

    if (isAcpKernel(chat.engine) || isCliKernel(chat.engine)) {
      // ACP cancel is a notification, so there is nothing to await: the turn is
      // closed out here and the prompt response that follows is ignored
      // (finishAcpTurn returns early once status is 'stopped').
      // Anything the conversation was still asking for is settled as denied,
      // because the kernel is waiting on those answers.
      this.approvals.cancelForChat(chat.id);
      const kernel = this.acp.get(chat.engine);
      // A CLI kernel cancels its process, so it must be reachable even before it
      // has named its session.
      if (kernel && (chat.sessionId || isCliKernel(chat.engine))) kernel.cancel(chat.sessionId);
      chat.status = 'stopped';
      chat.push({ kind: 'error', role: 'engine', text: '已停止本轮回复' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'stopped' });
      return { ok: true };
    }

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
    this.approvals.cancelForChat(id);
    this.dispose(chat, 'closed');
    this.chats.delete(id);
    this.emit({ event: 'chat.closed', chatId: id });
    return { ok: true };
  }

  disposeAll() {
    this.approvals.disposeAll();
    for (const chat of [...this.chats.values()]) this.dispose(chat, 'shutdown');
    try { this.codex?.dispose(); } catch { /* already gone */ }
    this.codex = null;
    this.codexThreads.clear();
    for (const kernel of this.acp.values()) {
      try { kernel.dispose(); } catch { /* already gone */ }
    }
    this.acp.clear();
    this.acpSessions.clear();
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

    // The sdk profile knows no providers by itself: the shared settings file
    // that used to supply them is absent, and the desktop profile's patch is not
    // read here. Without this overlay the handshake is refused outright
    // ("no adapter registered for provider") and the turn never even starts.
    let patch = null;
    try {
      const route = routeConfig();
      patch = ensureOverlay({ ...route, model: chat.model || route.model });
    } catch (err) {
      chat.push({ kind: 'local', role: 'engine', text: `DSH 覆盖配置没写成：${err.message}` });
    }
    const child = spawn(process.execPath, runtimeArgs(bin, { patch }), {
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
    try {
      await chat.request(
        'initialize',
        { cwd: chat.cwd, provider: chat.provider, model: chat.model },
        INIT_TIMEOUT_MS,
      );
    } catch (err) {
      const message = String(err?.message ?? err);
      if (!patch && /no adapter registered for provider/.test(message)) {
        throw new Error(
          `${message}｜这个 provider 不在 TermDesk 写的覆盖配置里；` +
          'TERMDESK_CHAT_PROVIDER / TERMDESK_DSH_BASE_URL / TERMDESK_DSH_API_KEY_ENV 可以指定它',
        );
      }
      throw err;
    }
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
      // A turn that produced nothing must say so. Without this the phone showed a
      // turn that started and ended with no text and no error - indistinguishable
      // from a broken app, when the truth was that the runtime's own request/answer
      // path returned nothing. Measured on the phone: the model answers fine from
      // the same sandbox (direct API call, content "OK"), so an empty turn here is
      // a fact about the runtime's turn, and it is reported as one.
      const userSeq = chat.events.filter((e) => e.kind === 'message' && e.role === 'user').pop();
      const produced = chat.events.some((e) => (!userSeq || e.seq > userSeq.seq)
        && (e.kind === 'message' || e.kind === 'tool' || e.kind === 'tool_result' || e.kind === 'reasoning'));
      const emptyReason = ev.data?.reason?.kind ?? null;
      if (!produced) {
        const tail = String(chat.stderrTail ?? '').replace(/\s+/g, ' ').trim().slice(-300);
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: tail
            ? `DSH 这一轮没有返回任何内容（原因：${emptyReason ?? '未说明'}）。运行时最后的输出：${tail}`
            : `DSH 这一轮没有返回任何内容（原因：${emptyReason ?? '未说明'}），运行时也没有给出原因。`,
          name: 'warning',
        });
      }
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
