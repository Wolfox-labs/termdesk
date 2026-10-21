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
import { isScratchArtifact } from './kernels/acp.js';
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

/** The only option ids an engine's answer may be translated into. */

/** One line describing what Codex is asking for. */
const MAX_CHATS = 8;
const MAX_PROMPT_CHARS = 24000;
/**
 * How long a conversation with no traffic keeps its runtime alive.
 *
 * Each live chat holds a full Node harness with an open provider route (DSH) or
 * a kernel process (ACP), so an idle one is real memory on someone's desktop.
 * The clock is the manager's, not a socket's: a phone that disconnects must not
 * decide how long the conversation survives.
 */
const IDLE_TTL_MS = 45 * 60 * 1000;
const INIT_TIMEOUT_MS = 120 * 1000;
const PROMPT_TIMEOUT_MS = 30 * 60 * 1000;
const REAP_INTERVAL_MS = 60 * 1000;

/**
 * How recently a session's file must have been written for the phone to be warned that
 * somebody else may be in it.
 *
 * Two minutes rather than a few seconds: a turn that is thinking writes nothing for long
 * stretches, so a tight window would fall silent exactly while the desktop application is
 * in the middle of an answer — the case the warning exists for.
 */
const RECENT_WRITE_SECONDS = 120;

/**
 * How many messages may wait behind a running turn.
 *
 * Small on purpose: this is "I typed the next thing while it was answering", not a batch
 * interface. A queue nobody can see the end of is how a conversation ends up answering
 * questions the person has already moved past.
 */
const MAX_QUEUED_MESSAGES = 5;

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

/**
 * The DSH runtime this machine would start.
 *
 * Discovery lives in kernels/dsh-runtime.js because it is the same question the
 * kernel table answers: which entry, launched how, with which environment. The
 * installed desktop app keeps its CLI inside `app.asar`, so "the file exists" is
 * not a plain `fs.statSync` question — see kernels/asar.js.
 */
function dshRuntime() {
  return resolveDshRuntime();
}

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
function lookupKernel(id) {
  return getKernel(id);
}

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
  async send(id, text, opts = {}) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    const message = String(text ?? '').trim();
    if (message.length === 0) return { ok: false, code: 'empty_prompt', message: '消息不能为空' };
    if (message.length > MAX_PROMPT_CHARS) {
      return { ok: false, code: 'prompt_too_long', message: `消息过长（上限 ${MAX_PROMPT_CHARS} 字符）` };
    }
    // A slash line the desktop app would execute itself (/compact, /export, /goal,
    // /permission, ...) must not be sent to the model as text. The commands belong
    // to the host that owns the session, so only a live-attached DSH conversation
    // can run them - and they run WITHOUT a model turn.
    if (message.startsWith('/')) {
      const handled = await this.runLiveCommand(chat, message);
      if (handled) return handled;
    }
    // Waiting is the PC's job, not the person's. A kernel takes one prompt at a time, so a
    // message typed while the answer is still coming waits here and starts when the turn
    // ends. It is shown immediately, because a message that disappears until later reads
    // as a message that was lost.
    if (chat.status === 'running') {
      const queue = (chat.queue ??= []);
      if (queue.length >= MAX_QUEUED_MESSAGES) {
        return {
          ok: false,
          code: 'queue_full',
          message: `上一轮还在进行中，排队已满（${MAX_QUEUED_MESSAGES} 条），等它答完再发`,
        };
      }
      const line = chat.push({
        kind: 'message',
        role: 'user',
        text: message,
        optimistic: true,
        queued: true,
      });
      queue.push({ line, text: message, opts });
      chat.lastUsedAt = Date.now();
      this.emitEvent(chat, line);
      return { ok: true, queued: true, position: queue.length, userSeq: line };
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

    // One question, three answers. Before this the manager asked by name, so a
    // kernel it had not been taught fell through to the SDK driver and talked a
    // protocol it does not speak. A queued message starts through the same dispatch, so it
    // lives on its own.
    return this.dispatchTurn(chat, message, userSeq);
  }

  /**
   * Hand one prompt to whichever driver this conversation's kernel speaks.
   *
   * Split out of `send` so a queued message can start without re-entering the parts of
   * `send` that decide whether a message may be sent at all — and, more importantly,
   * without pushing a second copy of a line that is already on screen.
   */
  dispatchTurn(chat, message, userSeq) {
    const driver = turnDriver(chat.engine, lookupKernel);
    if (driver === 'app-server') return this.sendCodex(chat, message, userSeq);
    if (driver === 'acp') return this.sendAcp(chat, message, userSeq);
    if (driver === 'sdk') return this.sendDsh(chat, message, userSeq);
    return this.failUnsupported(chat, userSeq);
  }

  /**
   * Start the next queued message, if there is one and the conversation is free.
   *
   * Called where a turn ends. The line is already in the transcript — it was shown the
   * moment it was typed — so it is NOT pushed again: a second copy is exactly the
   * duplicate this queue must not create, and the phone would draw both. The echo slot is
   * set here instead, because that slot means "the kernel has not echoed this line yet",
   * which only becomes true now.
   *
   * A message that cannot start (a runtime that died while it waited) would stall the
   * queue behind itself forever, because a turn that never began never ends. So the
   * failure is written onto that line and the queue moves on.
   */
  /**
   * 内核自己写出来的文件，也要变成"产出物"。
   *
   * ACP 的 deliverable 只在**写文件工具**完成时才有；PPT / 表格这类是内核跑脚本写盘的
   * （python-pptx / openpyxl），于是手机上永远停在「作品生成中…」，也没有那张下载卡片
   * ——真机实测 report.pptx、equipment-ledger.xlsx 都生成了，界面却什么都没给。
   *
   * 兜底：回合结束时扫一遍工作目录，把本次新增的交付物补成 deliverable 事件。
   * 只认常见交付物扩展名，脚本/日志一类（isScratchArtifact）仍然排除。
   */
  settleProducedFiles(chat) {
    try {
      const dir = chat?.cwd;
      if (!dir || !fs.existsSync(dir)) return;
      const now = Date.now();
      // 只认最近 30 分钟内改过的文件：更早的一定是上一轮已经报过的。
      const since = now - 30 * 60 * 1000;
      const done = chat.delivered instanceof Set ? chat.delivered : (chat.delivered = new Set());
      const wanted = /\.(pptx|ppt|xlsx|xls|docx|pdf|html|htm|md|srt|csv|png|jpg|jpeg|webp|zip)$/i;
      const found = [];
      const walk = (d, depth) => {
        if (depth > 2) return;
        let entries = [];
        try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
        for (const entry of entries) {
          const name = entry?.name ?? '';
          if (!name || name.startsWith('.') || name === 'node_modules') continue;
          const full = path.join(d, name);
          if (entry.isDirectory()) { walk(full, depth + 1); continue; }
          if (!wanted.test(name) || isScratchArtifact(full) || done.has(full)) continue;
          let stat;
          try { stat = fs.statSync(full); } catch { continue; }
          if (stat.mtimeMs < since) continue;
          found.push({ path: full, name, bytes: stat.size, description: name });
        }
      };
      walk(dir, 0);
      if (found.length === 0) return;
      for (const file of found) done.add(file.path);
      const record = chat.push({
        kind: 'deliverable',
        role: 'assistant',
        text: found.map((f) => f.description).join('\n').slice(0, 4000),
        meta: { files: found },
      });
      this.emitEvent(chat, record.seq);
    } catch {
      // 兜底逻辑失败绝不能影响回合收尾。
    }
  }

  async drainQueue(chat) {
    if (!chat?.queue || chat.queue.length === 0) return false;
    if (chat.status === 'running') return false;
    const next = chat.queue.shift();
    chat.status = 'running';
    chat.lastUsedAt = Date.now();
    chat.pendingUserEcho = next.line;
    // It is being sent now, so it stops saying "waiting". The phone redraws this line from
    // the event; without it the marker would sit on a message the kernel has already seen.
    if (next.line.queued) {
      next.line.queued = false;
      this.emitEvent(chat, next.line);
    }
    const result = await this.dispatchTurn(chat, next.text, next.line);
    if (result?.ok === false) {
      chat.status = 'idle';
      const record = chat.push({
        kind: 'error',
        role: 'engine',
        text: `排队中的消息没有发出：${result.message ?? result.code ?? '未知原因'}`,
      });
      this.emitEvent(chat, record.seq);
      this.settleProducedFiles(chat);
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
      return this.drainQueue(chat);
    }
    return true;
  }

  /**
   * Abandon whatever is still waiting, out loud.
   *
   * Stopping means stopping: a queued message that fires right after a cancel is the
   * opposite of what the person asked for. The lines themselves stay — they were shown
   * when they were typed, and deleting somebody's words is worse than not sending them —
   * and one line says how many will not go.
   */
  dropQueue(chat, note) {
    const queued = chat.queue?.length ?? 0;
    if (queued === 0) return 0;
    chat.queue = [];
    const record = chat.push({
      kind: 'local',
      role: 'engine',
      text: `${note}：队列中的 ${queued} 条消息未发送`,
    });
    this.emitEvent(chat, record.seq);
    return queued;
  }

  /** One turn on the resident DSH SDK runtime. */
  async sendDsh(chat, message, userSeq) {
    // A conversation opened from history while the desktop app is running belongs
    // to that app's live agent. Sending here (a second runtime) would be refused
    // by the single-writer rule, so the message goes through the app's own API
    // instead and the answer is read back from the journal it appends to.
    if (chat.liveHost) return this.sendDshLive(chat, message, userSeq);
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
   * Run one slash command in the live session, or return null when the line is
   * ordinary text that merely starts with `/`.
   *
   * The phone's own `/` panel is a fixed short list, so the commands the desktop
   * app exposes (compact, export, goal, permission, feedback) were unreachable
   * there. They are executed by the host and do not consume a model turn, so this
   * is the same operation the desktop composer performs - not a fabricated one.
   */
  async runLiveCommand(chat, line) {
    if (chat.engine !== 'dsh') return null;
    const match = /^\/([^\s]+)\s*([\s\S]*)$/.exec(line);
    if (!match) return null;
    const name = match[1];
    const rest = match[2] ?? '';

    if (name === '命令' || name === 'help' || name === 'commands') {
      const catalog = chat.liveHost
        ? await liveListCommands(chat.liveHost, chat.sessionId)
        : { ok: false, commands: [], message: '桌面版 DeepSeek Harness 没有在运行，命令列表不可用' };
      const body = catalog.ok && catalog.commands.length > 0
        ? catalog.commands.map((c) => `/${c.name}${c.hint ? ' ' + c.hint : ''} — ${c.description}`).join('\n')
        : catalog.message ?? '没有可用命令';
      const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
      this.emitEvent(chat, userLine);
      this.pushAndEmit(chat, { kind: 'local', role: 'engine', text: body });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }

    // Without the live host there is no command registry to ask, so the honest
    // move is to say which commands need it rather than to guess at a catalog.
    if (!chat.liveHost) {
      if (['compact', 'export', 'goal', 'permission', 'feedback', 'plan'].includes(name)) {
        const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
        this.emitEvent(chat, userLine);
        this.pushAndEmit(chat, {
          kind: 'error',
          role: 'engine',
          text: `/${name} 由桌面版 DeepSeek Harness 执行；现在桌面版没有运行，这个命令不可用`,
        });
        return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
      }
      return null;
    }

    // Ask the host which commands exist, then only intercept names it really has:
    // an unknown `/word` is ordinary text and must reach the model unchanged.
    if (!Array.isArray(chat.liveCommands) || Date.now() - (chat.liveCommandsAt ?? 0) > 60_000) {
      const catalog = await liveListCommands(chat.liveHost, chat.sessionId);
      chat.liveCommands = catalog.ok ? catalog.commands : [];
      chat.liveCommandsAt = Date.now();
    }
    if (!chat.liveCommands.some((c) => c.name === name)) return null;

    const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
    this.emitEvent(chat, userLine);
    const result = await liveExecuteCommand(chat.liveHost, chat.sessionId, line);
    if (result.unknown) {
      this.pushAndEmit(chat, { kind: 'error', role: 'engine', text: `/${name} 没有执行：桌面端不认识这条命令` });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }
    if (!result.ok) {
      this.pushAndEmit(chat, { kind: 'error', role: 'engine', text: `/${name} 失败：${result.message}` });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }
    this.pushAndEmit(chat, {
      kind: result.kind === 'error' ? 'error' : 'local',
      role: 'engine',
      text: `/${name}：${result.text || (result.kind === 'error' ? '没有执行' : '已完成')}`,
    });
    return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
  }


  /**
   * One turn through the desktop app's live agent.
   *
   * TermDesk does not own the runtime here and must not touch the journal: it
   * queues the message over the app's own `/api/session/prompt`, then follows the
   * events the app appends. The user's line is shown immediately and reconciled
   * with the runtime's echo, exactly like the resident-runtime path.
   */
  async sendDshLive(chat, message, userSeq) {
    const write = lastWriteOf({ engine: 'dsh', id: chat.sessionId });
    const file = write?.path ?? null;
    if (!file) {
      const messageText = '找不到这条会话的日志，无法跟随桌面端的回复';
      chat.status = 'failed';
      chat.lastError = messageText;
      chat.push({ kind: 'error', role: 'engine', text: messageText });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'no_journal', message: messageText, userSeq };
    }

    // The stream is watched for the whole attachment, not just this turn: the
    // desktop user may type there too, and both ends must show one conversation.
    this.startLiveWatch(chat, chat.sessionId);

    chat.status = 'running';
    chat.ready = true;
    chat.livePendingText = message;
    chat.livePendingLine = userSeq ?? chat.pendingUserEcho ?? null;
    chat.liveQueued = false;
    chat.liveQueuedNoted = false;
    chat.liveEchoSeen = false;
    chat.liveTurnStarted = false;
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    if (chat.livePendingLine) this.emitEvent(chat, chat.livePendingLine);

    const result = await livePrompt(chat.liveHost, {
      sessionId: chat.sessionId,
      text: message,
      clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });

    if (!result.ok) {
      this.clearLivePending(chat);
      chat.status = 'failed';
      chat.lastError = result.message;
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      const hint = result.code === 'transport'
        ? '（桌面端的窗口可能已关闭：重新打开后再发，或改用本机运行时）'
        : '';
      chat.push({ kind: 'error', role: 'engine', text: `桌面端没有接受这条消息：${result.message}${hint}` });
      return { ok: false, code: 'live_prompt_failed', message: result.message, userSeq };
    }
    this.armLiveDeadline(chat);
    return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
  }

  /**
   * Follow the desktop app's journal for as long as this chat is attached.
   *
   * One watcher for the whole conversation (not one per turn): the desktop user can
   * type there at any time, and the phone is meant to show the same conversation.
   * It starts at the current end so history is not replayed twice.
   */
  /**
   * Follow the live conversation through the desktop app's own event stream.
   *
   * The stream carries the durable events AND the model's live chunk frames, which
   * is the only way to show an answer while it is being written: the v4 journal
   * keeps finished messages only. It reconnects on its own, and a fresh snapshot
   * after a reconnect fills the gap; durable events are deduped by seq.
   */
  startLiveWatch(chat, sessionId) {
    if (chat.liveStream) return;
    chat.liveClosed = false;
    chat.liveSeenSnapshot = false;
    if (typeof chat.liveLastSeq !== 'number') chat.liveLastSeq = -1;
    const open = () => {
      if (chat.liveClosed) return;
      chat.liveStream = followSession(chat.liveHost, {
        sessionId,
        onSnapshot: (snapshot) => this.handleLiveSnapshot(chat, snapshot),
        onEvent: (event) => this.handleLiveEvent(chat, event),
        onChunk: (frame) => this.handleLiveChunk(chat, frame),
        onError: () => {},
        onClose: () => {
          chat.liveStream = null;
          if (chat.liveClosed) return;
          chat.liveReconnect = setTimeout(open, 1200);
          chat.liveReconnect.unref?.();
        },
      });
    };
    open();
  }

  stopLiveTail(chat) {
    chat.liveClosed = true;
    if (chat.liveReconnect) {
      clearTimeout(chat.liveReconnect);
      chat.liveReconnect = null;
    }
    if (chat.liveDeadline) {
      clearTimeout(chat.liveDeadline);
      chat.liveDeadline = null;
    }
    if (chat.liveStream) {
      try { chat.liveStream.close(); } catch { /* already closed */ }
      chat.liveStream = null;
    }
  }

  /**
   * The opening snapshot.
   *
   * On the first open it is history this side already seeded from the journal, so
   * it only sets the seq floor (the stream continues from cursor+1). After a
   * reconnect the same page is the gap filler: records above the floor are handed
   * to the normal event path.
   */
  handleLiveSnapshot(chat, snapshot) {
    const records = Array.isArray(snapshot?.records) ? snapshot.records : [];
    const cursor = typeof snapshot?.cursor === 'number' ? snapshot.cursor : null;
    if (!chat.liveSeenSnapshot) {
      chat.liveSeenSnapshot = true;
      if (cursor !== null) chat.liveLastSeq = Math.max(chat.liveLastSeq, cursor);
      return;
    }
    for (const record of records) {
      const event = record?.event;
      if (!event || typeof event.seq !== 'number' || event.seq <= chat.liveLastSeq) continue;
      chat.liveLastSeq = event.seq;
      this.handleLiveJournalEvent(chat, event);
    }
    if (cursor !== null) chat.liveLastSeq = Math.max(chat.liveLastSeq, cursor);
  }

  handleLiveEvent(chat, event) {
    if (!event) return;
    if (typeof event.seq === 'number') {
      if (event.seq <= chat.liveLastSeq) return;
      chat.liveLastSeq = event.seq;
    }
    this.handleLiveJournalEvent(chat, event);
  }

  /**
   * One live chunk frame from the app's `agent/assistant-stream`.
   *
   * `frame.chunk` is the same shape the resident runtime sends as `assistant/chunk`,
   * so the preview rules in chat/stream.js are reused rather than copied: deltas
   * grow one record that is re-emitted, and the finished `assistant/message` later
   * replaces it.
   */
  handleLiveChunk(chat, frame) {
    if (!frame || frame.type !== 'chunk' || !frame.chunk) return;
    this.handleSessionEvent(chat, {
      event: { type: 'assistant/chunk', data: { chunk: frame.chunk } },
    });
  }

  /** The state a pending live message owns; cleared on every exit path. */
  clearLivePending(chat) {
    chat.livePendingText = null;
    chat.livePendingLine = null;
    chat.liveQueued = false;
    chat.liveQueuedNoted = false;
    chat.liveEchoSeen = false;
    chat.liveTurnStarted = false;
    chat.pendingUserEcho = null;
    if (chat.liveDeadline) {
      clearTimeout(chat.liveDeadline);
      chat.liveDeadline = null;
    }
  }

  armLiveDeadline(chat) {
    if (chat.liveDeadline) clearTimeout(chat.liveDeadline);
    chat.liveDeadline = setTimeout(() => {
      if (chat.status !== 'running') return;
      chat.status = 'failed';
      chat.lastError = '等待桌面端回复超时';
      this.clearLivePending(chat);
      chat.push({ kind: 'error', role: 'engine', text: '等待桌面端回复超时（45 分钟）：可以重新发送，或到电脑上继续' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    }, 45 * 60 * 1000);
    chat.liveDeadline.unref?.();
  }

  /** The text of one inbox item the runtime recorded. */
  handleLiveJournalEvent(chat, event) {
    const type = event?.type;

    // Queue bookkeeping: this is how the desktop says "your message is waiting"
    // and later "it was deleted", and without it the phone kept showing a
    // cancelled message as still queued (and stayed "running" for ever).
    if (type === 'agent/inbox/spliced') {
      this.handleLiveInbox(chat, event);
      return;
    }

    if (type === 'user/message') {
      const normalized = dshEventToChatEvent(event);
      if (normalized?.role === 'user' && this.isLiveEcho(chat, normalized.text)) {
        chat.liveEchoSeen = true;
        if (chat.liveQueued) {
          chat.liveQueued = false;
          chat.liveQueuedNoted = false;
          this.pushAndEmit(chat, { kind: 'local', role: 'engine', text: '桌面端开始处理这条消息' });
        }
      }
    }
    // A turn that starts only after OUR echoed message is the turn we queued.
    // Until then, `turn/end` belongs to a turn that was already running there.
    if (type === 'turn/start' && chat.liveEchoSeen) chat.liveTurnStarted = true;

    this.handleSessionEvent(chat, { event });

    if (type === 'turn/end' && chat.liveEchoSeen && chat.liveTurnStarted) {
      this.finishLiveTurn(chat);
    }
  }

  isLiveEcho(chat, text) {
    const pending = (chat.livePendingText ?? '').trim();
    return pending.length > 0 && String(text ?? '').trim() === pending;
  }

  /**
   * Reconcile TermDesk's view with the desktop's inbox.
   *
   * The desktop records `agent/inbox/spliced`: an insertion when the message waits
   * behind a running turn, and a removal with `outcome: "canceled"` when the
   * person deletes it from the queue there. Both must reach the phone, otherwise a
   * deleted message still looks queued and the chat never leaves "running".
   */
  handleLiveInbox(chat, event) {
    const data = event?.data ?? {};
    if (data.target !== 'next-turn') return;
    const textOf = (item) => (Array.isArray(item?.content) ? item.content : [])
      .map((block) => (typeof block?.text === 'string' ? block.text : ''))
      .join('')
      .trim();
    const pending = (chat.livePendingText ?? '').trim();
    const inserted = Array.isArray(data.inserted) ? data.inserted : [];
    const ours = pending.length > 0 && inserted.some((item) => textOf(item) === pending);

    if (ours && !chat.liveEchoSeen) {
      chat.liveQueued = true;
      if (!chat.liveQueuedNoted) {
        chat.liveQueuedNoted = true;
        this.pushAndEmit(chat, {
          kind: 'local',
          role: 'engine',
          text: '已交给桌面端：这条消息排在电脑端当前回合之后，还没开始处理',
        });
      }
      return;
    }

    if (data.outcome === 'canceled' && chat.liveQueued && !chat.liveEchoSeen) {
      const line = chat.livePendingLine;
      this.clearLivePending(chat);
      chat.status = 'idle';
      if (line) {
        line.canceled = true;
        this.emitEvent(chat, line);
      }
      this.pushAndEmit(chat, {
        kind: 'error',
        role: 'engine',
        text: '这条消息没有执行：电脑端把排队中的它删除了。手机端现在和电脑一致——它不会再发出去',
      });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
      this.settleProducedFiles(chat);
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
      this.drainQueue(chat).catch(() => {});
    }
  }

  /** The live turn is over: only the status changes, the watcher keeps mirroring. */
  finishLiveTurn(chat) {
    if (chat.status !== 'running') return;
    this.clearLivePending(chat);
    chat.status = 'idle';
    this.settleProducedFiles(chat);
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
    this.notify(this.turnNotification(chat, 'ended'));
    this.drainQueue(chat).catch(() => {});
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
      const resolved = this.codexSpawnSpec('codex');
      if (!resolved?.bin) throw new Error('Codex app-server 启动入口不可用');
      const server = this.codexServerFactory({
        bin: resolved.bin,
        args: [...(resolved.args ?? []), 'app-server', '--listen', 'stdio://'],
        log: (line) => console.log(line),
      });
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
    // "进行中" promises what is STILL RUNNING on the PC. Only the app-server kernel
    // can be asked that; asking the others would spend a round trip on an answer
    // that does not exist.
    if (collectsLiveTerminals(chat.engine, lookupKernel)) {
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
    const adopted = [...this.chats.values()].find(
      (c) => c.sessionId === id && turnDriver(c.engine, lookupKernel) === 'app-server',
    );
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
  findChatByThread(threadId) {
    if (!threadId) return null;
    return this.codexThreads.get(threadId) ?? null;
  }

  /**
   * Fallback for an app-server request that does not name its thread.
   *
   * Kept as a method (the approval broker is wired to it) but no longer written in
   * terms of one kernel's name: it is "the newest conversation on a driver that
   * routes by thread".
   */
  newestCodexChat() {
    const list = [...this.chats.values()]
      .filter((c) => turnDriver(c.engine, lookupKernel) === 'app-server')
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
    this.notify(this.turnNotification(chat, failed ? 'failed' : 'ended'));
    // The turn is over, so anything typed during it can start now. A failed turn is not
    // drained: the next message would go into the same wall.
    if (!failed) this.drainQueue(chat).catch(() => {});
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
  /**
   * The slash commands this conversation can run.
   *
   * Only a live-attached DSH conversation has a real registry: the commands belong
   * to the desktop host that owns the session. Everything else says so instead of
   * returning an empty list that looks like "no commands exist".
   */
  async commandsFor(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.engine === 'dsh' && chat.liveHost) {
      const listed = await liveListCommands(chat.liveHost, chat.sessionId);
      if (!listed.ok) {
        return { ok: true, chatId: id, supported: false, commands: [], note: listed.message ?? '桌面端没有返回命令目录' };
      }
      return { ok: true, chatId: id, supported: true, commands: listed.commands, note: null };
    }
    return {
      ok: true,
      chatId: id,
      supported: false,
      commands: [],
      note: chat.engine === 'dsh'
        ? '桌面版 DeepSeek Harness 没有在运行：/compact 等命令由它执行'
        : '这个助手没有提供斜杠命令',
    };
  }

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
   * Refuse a turn on a kernel this build cannot drive.
   *
   * Reached when the table has no driver for the engine — an id from a newer phone,
   * or a typo. It fails the turn LOUDLY and names the engine: the alternative, which
   * this replaced, was falling through to the SDK driver and talking a protocol the
   * kernel may not speak, or doing nothing at all.
   */
  failUnsupported(chat, userSeq) {
    const message = unknownKernelMessage(chat.engine, lookupKernel)
      ?? `${chat.engine} 内核没有可用的驱动方式，请更新电脑端 TermDesk`;
    return this.failAcp(chat, message, userSeq, 'unsupported_kernel');
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

  /**
   * Open a recorded DSH session on its own runtime.
   *
   * The SDK runtime loads the persisted history itself (`session/resume`), so the
   * next prompt continues the same conversation: nothing is reconstructed from the
   * display transcript, and no text is ever re-fed as a prompt.
   *
   * Two facts are stated rather than guessed:
   *   - the working directory comes from the stored session header, because
   *     resuming a conversation in a different tree would silently change what its
   *     relative paths mean;
   *   - the model route comes from this machine's explicit DSH configuration, and
   *     the runtime applies it to the resumed agent (the protocol says so); the
   *     session's own last route is not reversible from the log.
   *
   * The desktop application may be holding the same session at the same time. The
   * persistence lock is per process, so this cannot be prevented from here — the
   * caller has already been told via `takeoverNote` that two writers can diverge.
   */
  async resumeDsh(engine, id) {
    let detail = null;
    try {
      detail = await readSession({ engine: 'dsh', id });
    } catch {
      detail = null;
    }
    const cwd = detail?.meta?.cwd || null;
    if (!cwd || !fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory()) {
      return {
        ok: false,
        code: 'missing_workspace',
        message: '原会话的工作目录不存在或无法读取，不能静默切换到其他目录',
      };
    }

    let route;
    try {
      route = routeConfig(process.env, {});
      const routeError = routeConfigError(route);
      if (routeError) return { ok: false, code: 'unconfigured', message: routeError };
    } catch (err) {
      return { ok: false, code: 'unconfigured', message: String(err?.message ?? err) };
    }

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: detail?.meta?.title || path.basename(cwd),
      cwd,
      engine,
      provider: route.provider,
      model: route.model,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    // Opening a recorded session must not TAKE the session. DSH's `session/resume`
    // opens a write handle, and it is refused while any other process (the desktop
    // app, most of the time) holds one — so attaching here would make "just look at
    // the history" fail, and, worse, would take ownership away from the app that is
    // showing the conversation right now. The handle is therefore taken when the
    // first message is actually sent (see ensureRuntime).
    chat.pendingResume = true;
    this.chats.set(chat.id, chat);

    // The recorded conversation is shown so the phone opens a conversation rather
    // than an empty page. It is display state only: the runtime owns the prompt
    // history, and this transcript is never sent to a model.
    for (const event of detail?.events ?? []) {
      const at = event.at ? Date.parse(event.at) : NaN;
      chat.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: Number.isNaN(at) ? undefined : at,
        name: event.name ?? null,
        meta: event.meta ?? null,
      });
    }

    // If the desktop app is running, this conversation is ITS to continue: it
    // already holds the write handle, and a second runtime would fork the journal.
    // Messages then go to the live agent through its own API (see sendDshLive),
    // which is the only way to continue the conversation without stealing it.
    const liveHost = discoverLiveHost({ fresh: true });
    if (liveHost) {
      chat.liveHost = liveHost;
      chat.kernelLive = true;
      chat.ready = true;
      // Mirror the conversation from the moment of attaching: messages typed on
      // the desktop must arrive here too, and the phone must never be the reason a
      // second writer exists.
      this.startLiveWatch(chat, id);
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已接入桌面版 DeepSeek Harness 的实时会话：消息直接进入桌面端的对话，回复也从那里读回',
      });
    } else {
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已打开本机保存的 DSH 会话记录 · 发送下一条消息时接管这次会话',
      });
    }
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
    this.notify(this.turnNotification(chat, failed ? 'failed' : 'ended'));
    // The turn is over, so anything typed during it can start now. A failed turn is not
    // drained: the next message would go into the same wall.
    if (!failed) this.drainQueue(chat).catch(() => {});
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

    // A conversation that belongs to the desktop app is cancelled THERE: this side
    // has no runtime to kill, and killing nothing would leave the app answering.
    if (chat.liveHost) {
      this.stopLiveTail(chat);
      liveCall(chat.liveHost, 'session/cancel', { request: { sessionId: chat.sessionId } }).catch(() => {});
      return this.stopTurn(chat, '已请求桌面端取消本轮回复');
    }

    // Anything the conversation was still asking for is settled as denied, for every
    // driver: the kernel is waiting on those answers, and a turn that is over must not
    // leave a permission prompt on the phone that nothing can resolve.
    this.approvals.cancelForChat(chat.id);

    // A shim kernel is an ACP kernel for driving purposes, and its process must be
    // reachable even before it has named a session. Asked of the table rather than of
    // a name; it is the same condition the old `isCliKernel` check expressed.
    const cancellableWithoutSession = kernelTier(chat.engine) === 'shim';

    switch (this.capabilitiesOf(chat.engine).cancel) {
      case 'ask-kernel': {
        // The kernel cancels its own turn and the thread stays resumable, so there is
        // nothing to kill locally.
        if (chat.threadId) this.codex?.interruptTurn(chat.threadId).catch(() => {});
        return this.stopTurn(chat);
      }
      case 'close-session': {
        // ACP cancel is a notification, so there is nothing to await: the turn is
        // closed out here and the prompt response that follows is ignored
        // (finishAcpTurn returns early once status is 'stopped').
        const kernel = this.acp.get(chat.engine);
        if (kernel && (chat.sessionId || cancellableWithoutSession)) kernel.cancel(chat.sessionId);
        return this.stopTurn(chat);
      }
      case 'kill-process': {
        this.dispose(chat, 'cancelled');
        return this.stopTurn(chat, '已停止（该内核协议无单轮取消，运行时已终止）');
      }
      default:
        // Unreachable for a selectable kernel (the registry only offers kernels that
        // have a driver), and said out loud rather than falling through to "stop the
        // runtime" - which would be a guess about a kernel this build does not know.
        return {
          ok: false,
          code: 'unsupported_kernel',
          message: unknownKernelMessage(chat.engine, lookupKernel)
            ?? `${chat.engine} 内核没有可用的停止方式`,
        };
    }
  }

  /** The part every stop shares: the note, the two events, and the answer. */
  stopTurn(chat, note) {
    chat.status = 'stopped';
    chat.push({ kind: 'error', role: 'engine', text: note ?? '已停止本轮回复' });
    // Stopping stops the whole thing, queue included: a message that fires seconds after a
    // cancel is the opposite of what was asked for.
    this.dropQueue(chat, '已停止');
    this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'stopped' });
    return { ok: true };
  }

  /** The contract, for one engine, in the shape this file asks its questions in. */
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

  // --- runtime plumbing --------------------------------------------------

  /** Start the DSH SDK runtime for a chat and complete its handshake once. */
  async ensureRuntime(chat) {
    if (chat.child && chat.child.exitCode === null && chat.ready) return;

    const runtime = dshRuntime();
    if (!runtime) {
      throw new Error(
        '找不到 DSH 入口：本机没有可用的 dsh 启动器，也没有检测到已安装的 DeepSeek Harness 桌面版',
      );
    }

    // The sdk profile's inherited provider configuration is not known to this
    // runtime, so require the DSH route to be explicit before writing an overlay.
    let patch = null;
    try {
      const route = routeConfig(process.env, { provider: chat.provider, model: chat.model });
      const routeError = routeConfigError(route);
      if (routeError) throw new Error(routeError);
      patch = ensureOverlay(route);
    } catch (err) {
      throw new Error(`DSH 配置无效：${err.message}`);
    }
    const child = spawn(runtime.command, launchArgs(runtime, { patch }), {
      cwd: chat.cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      // The Electron host needs ELECTRON_RUN_AS_NODE; a plain Node entry adds
      // nothing. Inherit everything else (PATH, credentials, DSH_HOME).
      env: launchEnv(runtime, process.env),
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
          'TERMDESK_CHAT_PROVIDER / TERMDESK_CHAT_MODEL / TERMDESK_DSH_BASE_URL / ' +
          'TERMDESK_DSH_API / TERMDESK_DSH_API_KEY_ENV 可以指定它',
        );
      }
      throw err;
    }
    // A chat opened from history takes ownership HERE, at the first real turn,
    // not when the transcript was shown. DSH refuses `session/resume` while another
    // process holds the write handle, and that refusal is the honest answer: the
    // desktop application is still in that conversation.
    if (chat.pendingResume) {
      try {
        await chat.request('session/resume', { sessionId: chat.sessionId }, INIT_TIMEOUT_MS);
        chat.pendingResume = false;
      } catch (err) {
        const message = String(err?.message ?? err);
        this.dispose(chat, 'resume-refused');
        throw new Error(
          `无法接管这次会话：${message}。它很可能正由桌面端 DeepSeek Harness 打开，`
          + '那里的对话框关掉后再试，或直接在那里继续。',
        );
      }
      chat.push({ kind: 'local', role: 'engine', text: '已接管本机 DSH 会话 · 后续消息延续原上下文' });
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
        this.settleProducedFiles(chat);
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'idle' });
        this.notify(this.turnNotification(chat, 'ended'));
        // The runtime is free again, so a message typed during the turn can go now.
        this.drainQueue(chat).catch(() => {});
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
    return handleStreamChunk(chat, ev, {
      push: (target, event) => target.push(event),
      emitEvent: (target, record, extra) => this.emitEvent(target, record, extra),
    });
  }

  pushAndEmit(chat, normalized) {
    const record = chat.push(normalized);
    this.emitEvent(chat, record);
    return record;
  }

  emitEvent(chat, record, extra = {}) {
    this.emit({ event: 'chat.event', chatId: chat.id, seq: record.seq, item: record, ...extra });
  }

  discardPreviews(chat, kind) {
    return discardStreamedPreviews(chat, kind, this);
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
    this.stopLiveTail(chat);
    this.failPending(chat, new Error('会话已结束'));
    // A kernel that owns its session store is stopped by asking it to stop, and the
    // conversation stays resumable: nothing of ours is running to kill.
    if (this.capabilitiesOf(chat.engine).ownsSessionStore) {
      this.clearCodexWatchdog(chat);
      if (chat.threadId) {
        this.codexThreads.delete(chat.threadId);
        if (chat.status === 'running') this.codex?.interruptTurn(chat.threadId).catch(() => {});
      }
      chat.ready = false;
      return;
    }
    // Everything below is the SDK runtime, which is a child process of ours.
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


export const CHAT_DEFAULTS = { MAX_CHATS, MAX_PROMPT_CHARS, IDLE_TTL_MS };
export { findDsh as findChatDsh };
// `Chat` moved to chat/chat.js; it is re-exported so this file stays the one
// place callers import a conversation from — the split must not change the
// facade (tools/chat-facade-test.js pins that).
export { Chat };
