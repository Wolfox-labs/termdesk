/**
 * ACP (Agent Client Protocol) adapter — ONE adapter for every ACP kernel.
 *
 * Why this exists: TermDesk's job is to remote-control mature coding agents, so
 * the kernel must own the session. Re-implementing conversation memory, history
 * listing and resume per kernel was the expensive mistake; ACP hands all three
 * to the kernel and keeps them identical across products:
 *
 *   session/new     new conversation (client picks the working directory)
 *   session/list    the kernel's own session index          <- history list
 *   session/load    attach to a past session + replay it    <- history body
 *   session/prompt  one turn, streamed as session/update    <- live answer
 *   session/cancel  stop the in-flight turn
 *
 * Verified locally against `opencode-cli.exe acp` (OpenCode) and `mimo acp`
 * (MiMo Code, an OpenCode derivative): both declare protocolVersion 1 with
 * loadSession + sessionCapabilities {list, resume, fork}.
 *
 * Wire facts that shaped this file:
 *   - newline-delimited JSON-RPC 2.0 over stdio; method names are lower-case
 *     and namespaced with a slash ("session/new"), unlike Codex's snake_case.
 *   - the agent streams `session/update` NOTIFICATIONS while a prompt runs; the
 *     `session/prompt` RESPONSE only arrives when the turn is over.
 *   - the agent may send client-directed REQUESTS ("session/request_permission").
 *     Those carry an id, and a turn that never answers them hangs forever, so
 *     every one of them is answered here (policy in `approvalPolicy`).
 *   - `session/cancel` is a notification, not a request: there is nothing to
 *     await, so cancellation is reported optimistically and the turn is closed
 *     out when its prompt response lands.
 */
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { AcpTerminals, ACP_TERMINAL_METHODS } from './acp-terminal.js';
import { kernelEnv, rememberSpawn, forgetSpawn } from '../spawnledger.js';

const PROTOCOL_VERSION = 1;
/** Requests other than a prompt are answered quickly or not at all. */
const REQUEST_TIMEOUT_MS = 60_000;
/** A turn may legitimately think for a long time; the answer streams meanwhile. */
const PROMPT_TIMEOUT_MS = 30 * 60_000;
/**
 * How long a permission question may stay unanswered.
 *
 * The kernel blocks the turn while it waits, so there is a deadline even when a
 * phone is reachable: a question left on a phone in a pocket must not hold a
 * turn open forever. On expiry the kernel's own policy default is used.
 */
const PERMISSION_TIMEOUT_MS = 5 * 60_000;
const MAX_TEXT = 4000;

/** Seconds-or-ms epoch -> ISO, and ISO -> ISO. Kernels disagree on the unit. */
function toIso(value) {
  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : new Date(parsed).toISOString();
  }
  if (typeof value !== 'number' || value <= 0) return null;
  const ms = value < 1e12 ? value * 1000 : value;
  try { return new Date(ms).toISOString(); } catch { return null; }
}

/**
 * ACP `content` is a discriminated union (text / image / audio / resource /
 * resource_link) and may also arrive as an array of blocks.
 */
export function acpContentToText(content) {
  if (content == null) return '';
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((item) => acpContentToText(item)).join('');
  if (typeof content !== 'object') return '';
  switch (content.type) {
    case undefined:
    case 'text':
      return typeof content.text === 'string' ? content.text : '';
    case 'resource_link':
      return content.uri ?? content.name ?? '';
    case 'resource': {
      const inner = content.resource ?? {};
      if (typeof inner.text === 'string') return inner.text;
      return inner.uri ?? '';
    }
    case 'image':
      return content.uri ? `[图片] ${content.uri}` : '[图片]';
    case 'audio':
      return '[音频]';
    default:
      return typeof content.text === 'string' ? content.text : '';
  }
}

/** Tool-call content: text blocks, file diffs or terminal references. */
export function acpToolContentToText(content) {
  if (!Array.isArray(content)) return acpContentToText(content);
  return content
    .map((item) => {
      if (item == null) return '';
      if (typeof item !== 'object') return String(item);
      if (item.type === 'content') return acpContentToText(item.content);
      if (item.type === 'diff') {
        const head = item.path ? `--- ${item.path}` : '---';
        return `${head}\n${item.newText ?? item.oldText ?? ''}`;
      }
      if (item.type === 'terminal') return `terminal ${item.terminalId ?? ''}`.trim();
      return acpContentToText(item);
    })
    .filter(Boolean)
    .join('\n');
}

/**
 * One `session/update` -> chat events, in the SAME vocabulary the Codex and DSH
 * paths use (kind: message / reasoning / tool / tool_result / engine_note), so
 * the phone renders every kernel through one schema.
 *
 * Records never carry a `type` field: encodeFrame spreads payload after `type`,
 * so a top-level `type` would silently overwrite the wire frame type.
 */
export function acpUpdateToChatEvents(update) {
  if (!update || typeof update !== 'object') return [];
  switch (update.sessionUpdate) {
    case 'user_message_chunk': {
      const text = acpContentToText(update.content).trim();
      return text ? [{ kind: 'message', role: 'user', text, echo: true }] : [];
    }
    case 'agent_message_chunk': {
      const text = acpContentToText(update.content);
      return text ? [{ kind: 'message', role: 'assistant', text, streaming: true }] : [];
    }
    case 'agent_thought_chunk': {
      const text = acpContentToText(update.content);
      return text ? [{ kind: 'reasoning', role: 'assistant', text, streaming: true }] : [];
    }
    case 'tool_call': {
      const label = update.title || update.kind || 'tool';
      const detail = acpToolContentToText(update.content);
      const locations = Array.isArray(update.locations)
        ? update.locations.map((l) => l?.path ?? l).filter(Boolean).join(', ')
        : '';
      return [{
        kind: 'tool',
        role: 'assistant',
        text: [label, detail, locations].filter(Boolean).join('  ').slice(0, MAX_TEXT),
        name: update.kind ?? 'tool_call',
        meta: { state: update.status ?? 'in_progress', toolCallId: update.toolCallId ?? null },
      }];
    }
    case 'tool_call_update': {
      // A pure status flip (pending -> in_progress) carries nothing readable.
      const detail = acpToolContentToText(update.content);
      const status = update.status ?? 'completed';
      if (!detail && status === 'in_progress') return [];
      return [{
        kind: 'tool_result',
        role: 'tool',
        text: (detail || `[${status}]`).slice(0, MAX_TEXT),
        name: update.kind ?? 'tool_call',
        meta: { state: status, toolCallId: update.toolCallId ?? null },
      }];
    }
    case 'plan': {
      const entries = Array.isArray(update.entries) ? update.entries : [];
      const text = entries
        .map((e) => `${e?.status === 'completed' ? '[x]' : '[ ]'} ${e?.content ?? e?.title ?? ''}`.trim())
        .filter(Boolean)
        .join('\n');
      return text ? [{ kind: 'engine_note', role: 'engine', text: text.slice(0, MAX_TEXT), name: 'plan' }] : [];
    }
    case 'current_mode_update': {
      const mode = update.currentModeId ?? update.mode ?? null;
      return mode ? [{ kind: 'engine_note', role: 'engine', text: `模式：${mode}`, name: 'mode' }] : [];
    }
    default:
      // available_commands_update and future variants carry no transcript value.
      return [];
  }
}

/** One `session/list` row -> the phone's SessionInfo shape. */
export function acpSessionToSessionInfo(kernelId, session) {
  const id = session?.sessionId ?? session?.id ?? null;
  if (!id) return null;
  return {
    engine: kernelId,
    id,
    title: session.title ?? null,
    cwd: session.cwd ?? null,
    createdAt: toIso(session.createdAt ?? session.updatedAt),
    updatedAt: toIso(session.updatedAt),
    sizeBytes: 0,
    path: '',
    native: true,
  };
}

/** Pick the option that matches the chat's approval policy. */
export function choosePermissionOption(options, policy) {
  const list = Array.isArray(options) ? options : [];
  if (list.length === 0) return null;
  const byKind = (kind) => list.find((o) => o?.kind === kind);
  if (policy === 'deny') {
    return byKind('reject_once') ?? byKind('reject_always')
      ?? list.find((o) => /reject|deny|no\b|取消|拒绝/i.test(String(o?.name ?? ''))) ?? null;
  }
  return byKind('allow_once') ?? byKind('allow_always')
    ?? list.find((o) => /allow|approve|yes\b|允许|同意/i.test(String(o?.name ?? ''))) ?? null;
}

/**
 * One ACP agent process, shared by every chat on that kernel.
 *
 * ACP servers are multi-session, so one process serves the whole conversation
 * list and routing happens on the session id — the same shape as the shared
 * Codex app-server, and the reason history and live chat can be one path.
 */
export class AcpKernel extends EventEmitter {
  constructor({ id, label = null, bin, args = ['acp'], cwd = null, log = console.log } = {}) {
    super();
    this.id = id;
    this.label = label ?? id;
    this.bin = bin;
    this.args = args;
    this.cwd = cwd;
    this.log = log;
    this.child = null;
    this.starting = null;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = '';
    this.stderrTail = '';
    this.capabilities = null;
    this.authMethods = [];
    /** What each live session offers (models / modes / config options). */
    this.sessionMeta = new Map();
    /**
     * Terminals this kernel asked us to run.
     *
     * ACP puts the command line on the client side, so a conversation's
     * terminals are ours: the phone can list them, read them and type into them.
     * See `acp-terminal.js` for why they are pipes and not a PTY.
     */
    this.terminals = new AcpTerminals({ onEvent: (event) => this.emit('terminal', event) });
    /** Allow tool permissions by default: the PC owner already opted into full
     *  control of this machine. Every decision is surfaced to the transcript. */
    this.approvalPolicy = process.env.TERMDESK_ACP_APPROVE === 'deny' ? 'deny' : 'allow';
    this.disposed = false;
  }

  get running() {
    return Boolean(this.child && this.child.exitCode === null);
  }

  async ensureStarted() {
    if (this.disposed) throw new Error(`ACP kernel ${this.id} disposed`);
    if (this.running) return this;
    if (this.starting) return this.starting;
    this.starting = (async () => {
      const child = spawn(this.bin, this.args, {
        cwd: this.cwd ?? undefined,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
        // Marked and recorded so a later run can clean up after a crash: on
        // Windows a killed parent does not take its children with it.
        env: kernelEnv(),
      });
      this.child = child;
      rememberSpawn(child.pid, `acp:${this.id}`);
      child.once('exit', () => forgetSpawn(child.pid));
      this.child = child;
      this.buffer = '';
      this.stderrTail = '';

      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (chunk) => this.onStdout(chunk));
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (chunk) => {
        // Diagnostics only; never echoed to the phone.
        this.stderrTail = (this.stderrTail + chunk).slice(-4000);
      });

      child.on('error', (err) => {
        this.log(`[acp:${this.id}] 启动失败：${err?.message ?? err}`);
        this.child = null;
        for (const [, p] of this.pending) p.reject(new Error(`ACP 内核无法运行：${err?.message ?? err}`));
        this.pending.clear();
        this.emit('exit', -1);
      });
      child.on('exit', (code) => {
        this.child = null;
        for (const [, p] of this.pending) p.reject(new Error(`ACP 内核已退出（${code}）`));
        this.pending.clear();
        this.emit('exit', code);
      });

      const result = await this.request('initialize', {
        protocolVersion: PROTOCOL_VERSION,
        // fs stays on the kernel's side: the phone's file browser talks to the
        // agent's own fs frames, so delegating file reads here would be a second
        // owner for the same files. Terminals are different - they are the one
        // thing the phone must own to be able to show and drive a command line.
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: true },
        clientInfo: { name: 'termdesk-pc-agent', title: 'TermDesk', version: '0.1' },
      }, { timeout: 30_000 });

      this.capabilities = result?.agentCapabilities ?? {};
      this.authMethods = Array.isArray(result?.authMethods) ? result.authMethods : [];
      this.agentInfo = result?.agentInfo ?? null;
      this.emit('ready', this.capabilities);
      return this;
    })().finally(() => { this.starting = null; });
    return this.starting;
  }

  onStdout(chunk) {
    this.buffer += chunk;
    let index;
    while ((index = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, index).trim();
      this.buffer = this.buffer.slice(index + 1);
      if (!line) continue;
      let message;
      try { message = JSON.parse(line); } catch { continue; }

      if (message.id !== undefined && message.method === undefined) {
        const pending = this.pending.get(message.id);
        if (!pending) continue;
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        if (message.error) pending.reject(new Error(`${message.error.message ?? 'rpc error'} (${message.error.code ?? ''})`));
        else pending.resolve(message.result);
        continue;
      }
      if (message.id !== undefined && message.method) {
        this.answerServerRequest(message);
        continue;
      }
      if (message.method === 'session/update') {
        const sessionId = message.params?.sessionId ?? null;
        this.emit('update', sessionId, message.params?.update ?? {});
        continue;
      }
      this.emit('notification', message.method, message.params ?? {});
    }
  }

  /**
   * Answer a kernel -> client request.
   *
   * The only one that matters in practice is `session/request_permission`: an
   * unanswered permission request blocks the turn forever. The decision is
   * surfaced to the caller (so it lands in the transcript) and never silently
   * swallowed.
   */
  answerServerRequest(message) {
    const method = message.method;
    if (method === 'session/request_permission') {
      const params = message.params ?? {};
      const options = Array.isArray(params.options) ? params.options : [];
      const defaultOption = choosePermissionOption(options, this.approvalPolicy);
      const defaultOptionId = defaultOption ? (defaultOption.optionId ?? defaultOption.id) : null;

      // Nothing may be sent to the kernel until the question is answered, and it
      // must be answered exactly once — a second reply to the same request id
      // would be a protocol error.
      let answered = false;
      const respond = (optionId) => {
        if (answered) return false;
        answered = true;
        clearTimeout(timer);
        const chosen = options.find((o) => (o.optionId ?? o.id) === optionId) ?? null;
        this.send({
          jsonrpc: '2.0',
          id: message.id,
          result: chosen
            ? { outcome: { outcome: 'selected', optionId: chosen.optionId ?? chosen.id } }
            : { outcome: { outcome: 'cancelled' } },
        });
        return true;
      };
      const timer = setTimeout(() => respond(defaultOptionId), PERMISSION_TIMEOUT_MS);

      // The decision itself belongs to the caller (the chat manager asks the
      // phone). This only guarantees the kernel is never left waiting forever.
      this.emit('permission', {
        sessionId: params.sessionId ?? null,
        toolCall: params.toolCall ?? null,
        options,
        defaultOptionId,
        respond,
        policy: this.approvalPolicy,
      });
      return;
    }
    // The command line a conversation uses. The kernel asks, we run it, and the
    // phone gets to see (and drive) the process — the VS Code + Copilot shape.
    if (ACP_TERMINAL_METHODS.has(method)) {
      const params = message.params ?? {};
      const reply = (result) => this.send({ jsonrpc: '2.0', id: message.id, result });
      const fail = (err) => this.send({
        jsonrpc: '2.0',
        id: message.id,
        error: { code: -32603, message: String(err?.message ?? err) },
      });
      try {
        switch (method) {
          case 'terminal/create':
            reply(this.terminals.create(params));
            break;
          case 'terminal/output':
            reply(this.terminals.output(params));
            break;
          case 'terminal/wait_for_exit':
            // Answered asynchronously: the kernel must not be blocked while a
            // command runs, and it is the kernel that decides when to wait.
            this.terminals.waitForExit(params).then(reply).catch(fail);
            break;
          case 'terminal/kill':
            reply(this.terminals.kill(params));
            break;
          case 'terminal/release':
            reply(this.terminals.release(params));
            break;
          default:
            fail(new Error(`termdesk does not implement ${method}`));
        }
      } catch (err) {
        fail(err);
      }
      return;
    }
    // fs/* and anything else unknown: we declared no support, so say so instead
    // of hanging.
    this.emit('serverRequest', method, message.params ?? {});
    this.send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: `termdesk does not implement ${method}` } });
  }

  send(value) {
    const child = this.child;
    if (!child || child.exitCode !== null) return false;
    try { child.stdin.write(`${JSON.stringify(value)}\n`); } catch { return false; }
    return true;
  }

  request(method, params, { timeout = REQUEST_TIMEOUT_MS } = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`ACP ${method} 超时`));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer, method });
      if (!this.send({ jsonrpc: '2.0', id, method, params })) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new Error('ACP 内核未在运行'));
      }
    });
  }

  /** JSON-RPC notification: no id, no response. */
  notify(method, params) {
    return this.send({ jsonrpc: '2.0', method, params });
  }

  async call(method, params, options) {
    await this.ensureStarted();
    return this.request(method, params, options);
  }

  // ---- typed surface ------------------------------------------------------

  /**
   * `authenticate` is only called when the kernel says it needs it: OpenCode
   * declares an auth method that is really "run `opencode auth login` in a
   * terminal", so calling it up front would be noise. The first session call
   * reports the failure and the caller retries once after authenticating.
   */
  async authenticate(methodId) {
    return this.call('authenticate', { methodId }, { timeout: 60_000 });
  }

  /** The kernel's own session index. Absent capability -> honest empty list. */
  async listSessions({ cwd } = {}) {
    if (!this.capabilities?.sessionCapabilities || !('list' in this.capabilities.sessionCapabilities)) {
      return { supported: false, sessions: [] };
    }
    const params = {};
    if (cwd) params.cwd = cwd;
    const result = await this.call('session/list', params);
    const sessions = Array.isArray(result?.sessions) ? result.sessions : [];
    return { supported: true, sessions };
  }

  async newSession({ cwd, mcpServers = [] } = {}) {
    const result = await this.call('session/new', { cwd, mcpServers });
    const sessionId = result?.sessionId ?? result?.session?.sessionId ?? null;
    if (!sessionId) throw new Error('session/new 未返回会话标识');
    // session/new hands back what this conversation can be changed into:
    //   models        { currentModelId, availableModels[] }
    //   modes         { currentModeId, availableModes[] }
    //   configOptions configId -> selectable values (the generic ACP shape)
    // Kept so the phone can offer a real choice instead of a free-text guess.
    this.sessionMeta.set(sessionId, {
      models: result?.models ?? null,
      modes: result?.modes ?? null,
      configOptions: Array.isArray(result?.configOptions) ? result.configOptions : [],
    });
    return sessionId;
  }

  /**
   * Change the model of a live session.
   *
   * Two shapes are in the wild: `session/set_model` (what OpenCode answers with
   * its own `_meta`) and the generic `session/set_config_option`. The first is
   * tried, the second is the fallback, and both were verified against
   * opencode-cli 1.3.16 on this machine.
   */
  async setModel(sessionId, modelId) {
    if (!modelId) return false;
    try {
      await this.call('session/set_model', { sessionId, modelId }, { timeout: 30_000 });
    } catch (err) {
      await this.call('session/set_config_option', { sessionId, configId: 'model', value: modelId }, { timeout: 30_000 });
    }
    const meta = this.sessionMeta.get(sessionId);
    if (meta?.models) meta.models.currentModelId = modelId;
    return true;
  }

  /**
   * The session's own switch surface, exactly as the kernel declared it in
   * `session/new`: permission/agent modes and the generic config options.
   * Read-only - what to render is the phone's decision, not this adapter's.
   */
  sessionOptions(sessionId) {
    const meta = this.sessionMeta.get(sessionId);
    if (!meta) return { modes: null, configOptions: [] };
    return { modes: meta.modes ?? null, configOptions: meta.configOptions ?? [] };
  }

  /**
   * Switch the session's permission / agent mode (`session/set_mode`).
   *
   * This is the kernel's own switch - Command Code calls one of them "Bypass
   * Permissions", OpenCode calls them build/plan - and it takes effect on the
   * session immediately, not on the next turn like a model.
   */
  async setMode(sessionId, modeId) {
    if (!modeId) throw new Error('缺少模式 id');
    await this.call('session/set_mode', { sessionId, modeId }, { timeout: 30_000 });
    const meta = this.sessionMeta.get(sessionId);
    if (meta?.modes) meta.modes.currentModeId = modeId;
    return true;
  }

  /** The models this session says it can switch to. */
  availableModels(sessionId) {
    const meta = this.sessionMeta.get(sessionId);
    const list = meta?.models?.availableModels;
    if (!Array.isArray(list)) return { current: null, models: [] };
    return {
      current: meta.models.currentModelId ?? null,
      models: list.map((m) => ({
        id: m?.modelId ?? m?.id ?? null,
        label: m?.name ?? m?.modelId ?? null,
      })).filter((m) => m.id),
    };
  }

  /**
   * Attach to a past session. The kernel replays the transcript as
   * `session/update` notifications BEFORE this resolves, so callers must have
   * their routing in place first (see ChatManager.resumeAcp).
   */
  async loadSession(sessionId, { cwd, mcpServers = [] } = {}) {
    if (this.capabilities && this.capabilities.loadSession === false) {
      throw new Error('该内核不支持打开历史会话');
    }
    const params = { sessionId, mcpServers };
    if (cwd) params.cwd = cwd;
    return this.call('session/load', params, { timeout: 120_000 });
  }

  async prompt(sessionId, text) {
    const result = await this.call(
      'session/prompt',
      { sessionId, prompt: [{ type: 'text', text }] },
      { timeout: PROMPT_TIMEOUT_MS },
    );
    return { stopReason: result?.stopReason ?? null };
  }

  /** Interrupt: ACP defines this as a notification. */
  cancel(sessionId) {
    return this.notify('session/cancel', { sessionId });
  }

  async closeSession(sessionId) {
    if (!this.capabilities?.sessionCapabilities || !('close' in this.capabilities.sessionCapabilities)) return false;
    await this.call('session/close', { sessionId });
    return true;
  }

  async forkSession(sessionId, { cwd } = {}) {
    const result = await this.call('session/fork', { sessionId, ...(cwd ? { cwd } : {}) });
    return result?.sessionId ?? null;
  }

  /** True once at least one session capability is usable. */
  sessionSupport() {
    const caps = this.capabilities?.sessionCapabilities ?? {};
    return {
      list: 'list' in caps,
      resume: 'resume' in caps,
      close: 'close' in caps,
      fork: 'fork' in caps,
      loadSession: Boolean(this.capabilities?.loadSession),
      image: Boolean(this.capabilities?.promptCapabilities?.image),
    };
  }

  dispose() {
    this.disposed = true;
    for (const [, p] of this.pending) p.reject(new Error('ACP 内核已关闭'));
    this.pending.clear();
    // The terminals are ours, so closing the kernel must close them too.
    this.terminals.dispose();
    try { this.child?.kill(); } catch { /* already gone */ }
    this.child = null;
  }
}