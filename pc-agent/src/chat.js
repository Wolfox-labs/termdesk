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
import { createApprovalHandlers } from './chat/approvals.js';
import { discardPreviews as discardStreamedPreviews, handleAssistantChunk as handleStreamChunk } from './chat/stream.js';
import { Chat } from './chat/chat.js';
import { decisionFor } from './kernels/codex.js';
import { CliKernel } from './kernels/cli.js';
import { ensureOverlay, routeConfig, routeConfigError } from './kernels/dsh.js';
import { launchArgs, launchEnv, resolveDshRuntime } from './kernels/dsh-runtime.js';
import { discoverLiveHost, followSession, liveCall, liveExecuteCommand, liveListCommands, livePrompt } from './kernels/dsh-live.js';
import { chatEngineIds, getKernel, isAcpKernel, isCliKernel, kernelTier, shimSpec, spawnSpec } from './kernels/registry.js';
import {
  canResume,
  collectsLiveTerminals,
  kernelCapabilities,
  modelRoute,
  turnDriver,
  unknownKernelMessage,
} from './kernels/contract.js';
import { ApprovalBroker, OPTIONS } from './approvals.js';
import { dshEventToChatEvent, lastWriteOf, readSession } from './sessions.js';
import { IDLE_TTL_MS, INIT_TIMEOUT_MS, MAX_CHATS, MAX_PROMPT_CHARS, PROMPT_TIMEOUT_MS, REAP_INTERVAL_MS, RECENT_WRITE_SECONDS, dshRuntime, lookupKernel } from './chat/shared.js';
import { liveMethods } from './chat/live.js';
import { codexMethods } from './chat/codex.js';
import { terminalMethods } from './chat/terminals.js';
import { acpMethods } from './chat/acp.js';
import { queueMethods } from './chat/queue.js';
import { runtimeMethods } from './chat/runtime.js';

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

/** Where the DSH entry is, as a path a person can read; '' when not found. */
function findDsh() {
  const runtime = dshRuntime();
  return runtime ? (runtime.script ?? runtime.command) : '';
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

/**
 * Read a kernel descriptor for the contract to answer about.
 *
 * `getKernel` already returns the public shape (id, transport, resume,
 * liveTerminals, modelRoute), so the contract needed no new lookup. It is named
 * here because two callers need it and because a single place to change is the
 * point of this refactor.
 */

/**
 * Turn a declared model route into the values a chat starts with.
 *
 * The declaration is the kernel's; this is the one place that knows what the three
 * kinds MEAN, so a caller can never honour half of one.
 */
function routeForChain(kind) {
  switch (kind) {
    case 'kernel-config':
      // The kernel reads its own config, which codex.get / codex.apply manage.
      return { kind, provider: null, model: null };
    case 'pinned':
      // The runtime has a usable default, but the machine's owner may pin one:
      // a cost decision belongs to whoever pays.
      return { kind, provider: null, model: process.env.TERMDESK_ACP_MODEL || null };
    default:
      return { kind: 'default', ...defaultRoute() };
  }
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

export class ChatManager {
  /**
   * @param {object} [options]
   * @param {import('./notify.js').Notifier|null} [options.notifier] where "tell the phone
   *   about this" goes. Optional, because tests and probes run without one — and a
   *   notification must never be the reason a turn fails.
   */
  constructor({
    notifier = null,
    codexSpawnSpec = spawnSpec,
    codexServerFactory = (options) => new CodexAppServer(options),
  } = {}) {
    this.chats = new Map();
    this.onEvent = null;
    this.notifier = notifier;
    this.codexSpawnSpec = codexSpawnSpec;
    this.codexServerFactory = codexServerFactory;
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
    // A question the kernel is blocked on is the most useful thing this side can tell a
    // phone that is not looking, so it is reported the moment it is registered.
    this.approvals.on('requested', (request) => this.notify({
      kind: 'approval',
      chatId: request?.chatId ?? null,
      engine: request?.engine ?? null,
      requestId: request?.requestId ?? null,
      title: request?.title ?? '内核在等你的回答',
      text: request?.detail ?? '',
    }));
    /**
     * Every approval-facing method below is mixed in from `chat/approvals.js`,
     * with its seams passed explicitly: which chat a Codex thread belongs to,
     * which chat an ACP session belongs to, and how to write the decision back
     * into that conversation's transcript.
     */
    const approvalHandlers = createApprovalHandlers({
      broker: this.approvals,
      findChatByThread: (threadId) => (threadId ? this.codexThreads.get(threadId) ?? null : null),
      newestCodexChat: () =>
        this.newestCodexChat(),
      acpChatForSession: (sessionId) => (sessionId ? this.acpSessions.get(sessionId) : null),
      chatById: (id) => this.chats.get(id) ?? null,
      pushAndEmit: (chat, event) => this.pushAndEmit(chat, event),
    });
    Object.assign(this, approvalHandlers);
    approvalHandlers.watch();
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

  /**
   * Tell the phone something it should know even if it is not looking.
   *
   * Wrapped in a try on purpose: a notification is a courtesy, and no courtesy is worth
   * losing a turn over. With no notifier (tests, probes) this does nothing.
   */
  notify(entry) {
    try {
      this.notifier?.push(entry);
    } catch {
      // Never let "telling somebody" break "doing the work".
    }
  }

  /**
   * The notification for a turn that just ended: which conversation, and what it said.
   *
   * The last thing the assistant said, cut short. "Done" on its own sends the person into
   * the app to find out whether it was worth opening — which is the trip this exists to
   * save. A failed turn says so instead of pretending something finished.
   */
  turnNotification(chat, state) {
    const last = [...chat.events].reverse().find(
      (e) => e.kind === 'message' && e.role === 'assistant' && e.text?.trim(),
    );
    return {
      kind: state === 'failed' ? 'turn_failed' : 'turn_done',
      chatId: chat.id,
      sessionId: chat.sessionId ?? null,
      engine: chat.engine,
      title: chat.title,
      text: last ? last.text.replace(/\s+/g, ' ').trim().slice(0, 120) : '',
    };
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
    // Where the default model comes from is a property of the KERNEL, declared in
    // the table, not something the manager infers from its name.
    const route = routeForChain(modelRoute(eng, lookupKernel));
    let dshRoute = null;
    if (eng === 'dsh') {
      dshRoute = routeConfig(process.env, {
        provider: provider || route.provider,
        model: model || route.model,
      });
      const routeError = routeConfigError(dshRoute);
      if (routeError) return { ok: false, code: 'unconfigured', message: routeError };
    }
    const workdir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
    const chat = new Chat({
      id,
      title: (title && String(title).trim()) || '新对话',
      cwd: workdir,
      engine: eng,
      provider: provider || dshRoute?.provider || route.provider,
      model: model || dshRoute?.model || route.model,
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
    } else if (route === 'kernel-config') {
      // The kernel selects its own model from a config file this app also manages,
      // which is why the note points at that surface instead of naming a kernel.
      chat.push({ kind: 'local', role: 'engine', text: `模型 随 ${chat.engine} 配置（codex.get / codex.apply）` });
    }
    return { ok: true, chat: chat.summary() };
  }

  /** Adopt a recorded native session; the display transcript is never used as a prompt. */
  async resume({ engine, id, sessionPath }) {
    if (!CHAT_ENGINES.includes(engine)) return { ok: false, code: 'bad_engine', message: '不支持的引擎' };
    if (typeof id !== 'string' || !/^[a-zA-Z0-9_-]{1,160}$/.test(id)) {
      return { ok: false, code: 'bad_session', message: '会话标识无效' };
    }
    // Measured BEFORE anything is attached, and that ordering is the point: resuming a
    // Codex thread rewrites its rollout file — observed on a real session, where a 09-27
    // thread's file was touched the moment the phone resumed it — so a measurement taken
    // afterwards reports this side's own write as somebody else's.
    const writeBefore = lastWriteOf({ engine, id, sessionPath });
    const existing = [...this.chats.values()].find(c => c.engine === engine && c.sessionId === id);
    // Somebody is already in this session — another phone, or this one after a
    // reconnect. They get the SAME chat, because one session must not become two
    // runtimes that then disagree about what was said. That is takeover working, so the
    // answer says so rather than looking like a fresh open.
    if (existing) {
      return {
        ok: true,
        chat: existing.detail(),
        joined: true,
        note: this.takeoverNote({ joined: true, write: writeBefore }),
      };
    }
    // Resumability is the table's answer, and when the table says no it also carries
    // the sentence explaining why - so the refusal cannot drift from its reason.
    if (!canResume(engine, lookupKernel)) {
      const detail = getKernel(engine)?.detail;
      return {
        ok: false,
        code: 'resume_unsupported',
        message: detail
          ? `${engine} 暂不支持恢复：${detail}`
          : `${engine} 内核未提供经过验证的恢复接口`,
      };
    }
    // ACP kernels own their session store, so "open history" is the same operation
    // as "continue": the kernel replays it and the next message continues it.
    // Nothing is ever reconstructed from display text.
    const driver = turnDriver(engine, lookupKernel);
    const result = driver === 'acp'
      ? await this.resumeAcp(engine, id)
      : driver === 'app-server'
        ? await this.resumeCodex(id)
        : driver === 'sdk'
          ? await this.resumeDsh(engine, id)
          : { ok: false, code: 'resume_unsupported', message: `${engine} 内核未提供经过验证的恢复接口` };
    if (result?.ok) {
      // A live-attached chat writes nothing here: the desktop app owns the journal,
      // so the "two writers" warning would be false and is not repeated.
      const note = this.takeoverNote({
        joined: false,
        write: writeBefore,
        live: Boolean(result.chat?.liveAttached),
      });
      if (note) result.note = note;
    }
    return result;
  }

  /**
   * What the phone must be told when a session is taken over, or null when there is
   * nothing worth saying.
   *
   * Two different facts, and both belong in front of the person BEFORE they type.
   * Somebody else in this app is already in the session (the join in `resume`), and the
   * file behind it was written seconds ago — which is how the desktop application shows
   * up, since it is a third-party app we can neither ask nor lock out. Saying it out loud
   * is the whole mitigation: nothing else stands between two writers.
   *
   * [write] is handed in by `resume` instead of being measured here, because it has to be
   * measured BEFORE the kernel is attached: attaching touches the file. A caller that
   * leaves it out gets a fresh measurement, which is right for every use but that one.
   *
   * `lastWriteOf` answers null for kernels whose store we cannot see (the ACP family).
   * Null is "no evidence", never "safe", so in that case nothing is said rather than
   * something reassuring. The sentence itself says what was measured — a file changed —
   * and not who changed it, because a write time cannot tell us that.
   */
  takeoverNote({ joined, write = undefined, engine, id, sessionPath, live = false }) {
    const parts = [];
    if (joined) {
      parts.push('这条会话已经在另一台设备上打开：双方看到的是同一个运行进程，发消息会进同一段对话');
    }
    if (!live) {
      const evidence = write === undefined ? lastWriteOf({ engine, id, sessionPath }) : write;
      if (evidence && evidence.agoSeconds <= RECENT_WRITE_SECONDS) {
        parts.push(`这台电脑上这条会话的文件 ${evidence.agoSeconds} 秒前被写过（可能是桌面端应用）：两边同时写会让内容分叉`);
      }
    }
    return parts.length > 0 ? parts.join('；') : null;
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

  capabilitiesOf(engine) {
    return kernelCapabilities(engine, lookupKernel);
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

}

// Method groups live in ./chat/*.js so each protocol family stays readable;
// the prototype composition keeps `this` state and call sites unchanged.
Object.assign(ChatManager.prototype, liveMethods, codexMethods, terminalMethods, acpMethods, queueMethods, runtimeMethods);

export const CHAT_DEFAULTS = { MAX_CHATS, MAX_PROMPT_CHARS, IDLE_TTL_MS };
export { findDsh as findChatDsh };
// `Chat` moved to chat/chat.js; it is re-exported so this file stays the one
// place callers import a conversation from — the split must not change the
// facade (tools/chat-facade-test.js pins that).
export { Chat };
