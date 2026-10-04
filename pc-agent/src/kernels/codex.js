/**
 * Codex app-server adapter — the kernel's own session API.
 *
 * Replaces the old "spawn `codex exec` once per turn + parse ~/.codex/sessions
 * JSONL ourselves" approach. With app-server the kernel owns the session index
 * and the transcript, so TermDesk can stop reimplementing them:
 *
 *   thread/list    session index (title, cwd, updatedAt, preview)   <- history list
 *   thread/read    transcript for a thread (turns + items)          <- history body
 *   thread/start   new session                                      ┐
 *   thread/resume  continue a native session                        ├ open(ref)
 *   turn/start     send, streamed back as notifications             │
 *   turn/interrupt cancel the current turn                          ┘
 *
 * Verified against codex-cli 0.158.0-alpha.2.1:
 *   - newline-delimited JSON-RPC 2.0 over stdio
 *   - method names are lower-case snake ("thread/list", "turn/start")
 *   - notifications: "turn/started", "item/agentMessage/delta", ...
 *   - server->client requests exist too (approvals). They carry BOTH id and
 *     method; if we do not answer them the turn hangs forever, so they are
 *     answered explicitly and surfaced to the caller.
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { findCodex } from '../engines.js';

const REQUEST_TIMEOUT_MS = 60_000;
const MAX_TEXT = 4000;

/** ThreadItem variants we know how to render. Everything else is dropped. */
const ANSWER_ITEM_TYPES = new Set(['agentMessage']);

function textOf(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map((v) => (typeof v === 'string' ? v : v?.text ?? '')).join('');
  if (value && typeof value === 'object') return value.text ?? '';
  return '';
}

/**
 * One ThreadItem -> chat events, in the same vocabulary the phone already
 * renders for the DSH path (`kind`: message/reasoning/tool/tool_result/...).
 * `done` distinguishes the started preview from the authoritative completion.
 */
export function threadItemToChatEvents(item, { done }) {
  if (!item || typeof item.type !== 'string') return [];
  switch (item.type) {
    case 'userMessage': {
      // Only on completion: `item/started` and `item/completed` both arrive for
      // the same message, and the phone already shows the optimistic copy.
      if (!done) return [];
      const text = textOf(item.content).trim();
      return text ? [{ kind: 'message', role: 'user', text }] : [];
    }
    case 'agentMessage': {
      const text = typeof item.text === 'string' ? item.text : '';
      if (!text) return [];
      return [{ kind: 'message', role: 'assistant', text, streaming: !done }];
    }
    case 'reasoning': {
      if (!done) return [];
      const text = [textOf(item.summary), textOf(item.content)].filter(Boolean).join('\n').slice(0, MAX_TEXT);
      return text ? [{ kind: 'reasoning', role: 'assistant', text }] : [];
    }
    case 'commandExecution': {
      if (!done) {
        return [{ kind: 'tool', role: 'assistant', text: item.command ?? '', name: 'command_execution', meta: { state: 'running' } }];
      }
      return [{
        kind: 'tool_result',
        role: 'tool',
        text: String(item.aggregatedOutput ?? item.aggregated_output ?? '').slice(0, MAX_TEXT),
        name: 'command_execution',
        meta: { state: 'completed', exitCode: item.exitCode ?? item.exit_code ?? null },
      }];
    }
    case 'fileChange': {
      if (!done) return [];
      const changes = item.changes ?? [];
      return [{
        kind: 'tool_result', role: 'tool',
        text: changes.map((c) => `${c.kind ?? 'change'}: ${c.path ?? ''}`).join('\n'),
        name: 'file_change', meta: { state: 'completed' },
      }];
    }
    case 'mcpToolCall': {
      const label = [item.server, item.tool].filter(Boolean).join('/');
      if (!done) return [{ kind: 'tool', role: 'assistant', text: `${label} ${JSON.stringify(item.arguments ?? {})}`.slice(0, 500), name: 'mcp_tool_call', meta: { state: 'running' } }];
      return [{ kind: 'tool_result', role: 'tool', text: JSON.stringify(item.result ?? item.error ?? {}).slice(0, MAX_TEXT), name: 'mcp_tool_call', meta: { state: item.status ?? 'completed' } }];
    }
    case 'webSearch': {
      return [{ kind: 'tool', role: 'assistant', text: String(item.query ?? ''), name: 'web_search', meta: { state: done ? 'completed' : 'running' } }];
    }
    case 'plan': {
      if (!done) return [];
      return [{ kind: 'engine_note', role: 'engine', text: textOf(item.text ?? item.items).slice(0, MAX_TEXT), name: 'plan' }];
    }
    case 'error': {
      if (!done) return [];
      return [{ kind: 'engine_note', role: 'engine', text: String(item.message ?? ''), name: 'warning' }];
    }
    default:
      return [];
  }
}

/** A whole thread turn (from thread/read) -> chat events, user message first. */
export function turnToChatEvents(turn) {
  const out = [];
  for (const item of turn?.items ?? []) out.push(...threadItemToChatEvents(item, { done: true }));
  return out;
}

/**
 * A server notification -> chat events.
 * Deltas are emitted as `streaming: true` records; the authoritative
 * `item/completed` record that follows is pushed with `streaming: false` so the
 * manager can drop the previews (same rule the DSH path uses).
 */
export function notificationToChatEvents(method, params) {
  const delta = params?.delta;
  switch (method) {
    case 'item/started':
      return threadItemToChatEvents(params?.item, { done: false });
    case 'item/completed':
      return threadItemToChatEvents(params?.item, { done: true });
    case 'item/agentMessage/delta':
      return typeof delta === 'string' && delta ? [{ kind: 'message', role: 'assistant', text: delta, streaming: true }] : [];
    case 'item/reasoning/textDelta':
    case 'item/reasoning/summaryTextDelta':
      return typeof delta === 'string' && delta ? [{ kind: 'reasoning', role: 'assistant', text: delta, streaming: true }] : [];
    case 'turn/started':
      return [{ kind: 'turn', role: 'engine', text: '', meta: { state: 'started', turnId: params?.turn?.id ?? null } }];
    case 'turn/completed': {
      const turn = params?.turn ?? {};
      return [{
        kind: 'turn', role: 'engine', text: '',
        meta: { state: turn.status === 'failed' ? 'failed' : 'ended', turnId: turn.id ?? null, reason: turn.error?.message ?? null },
      }];
    }
    case 'error':
      return [{ kind: 'engine_note', role: 'engine', text: String(params?.message ?? params?.error ?? 'error'), name: 'warning' }];
    default:
      return [];
  }
}

/** Seconds-or-ms epoch from the kernel -> ISO string (Thread timestamps are seconds). */
function toIso(value) {
  if (typeof value !== 'number' || value <= 0) return null;
  const ms = value < 1e12 ? value * 1000 : value;
  try { return new Date(ms).toISOString(); } catch { return null; }
}

/**
 * A `thread/list` row -> the phone's SessionInfo shape.
 *
 * This is the session index the phone renders. It comes from the kernel, so a
 * conversation created in the Codex desktop app shows up on the phone with the
 * same title and timestamps, and no JSONL is parsed by TermDesk.
 */
export function threadSummaryToSession(thread) {
  return {
    engine: 'codex',
    id: thread.id,
    title: thread.name || thread.title || (thread.preview ?? '').split('\n')[0].slice(0, 60) || null,
    cwd: thread.cwd ?? null,
    createdAt: toIso(thread.createdAt),
    updatedAt: toIso(thread.updatedAt),
    sizeBytes: 0,
    path: '',
    native: true,
  };
}

/** A full thread (`thread/read`) -> the phone's SessionDetail shape. */
export function threadToSessionDetail(thread) {
  const events = [];
  for (const turn of thread.turns ?? []) {
    for (const event of turnToChatEvents(turn)) {
      events.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: toIso(turn.startedAt) ?? null,
        name: event.name ?? null,
        state: event.meta?.state ?? null,
        exitCode: event.meta?.exitCode ?? null,
        tokens: null,
      });
    }
  }
  return {
    meta: {
      engine: 'codex',
      id: thread.id,
      cwd: thread.cwd ?? null,
      createdAt: toIso(thread.createdAt),
      title: thread.name || (thread.preview ?? '').split('\n')[0].slice(0, 60) || null,
      filePath: thread.path ?? null,
    },
    events,
    totalEvents: events.length,
    truncated: false,
  };
}

/**
 * Thread ids discoverable from the rollout directory.
 *
 * Why this exists: `thread/list` only returns the kernel's *recent window*
 * (measured: 9 of 54 root sessions on this machine), while `thread/read` and
 * `thread/resume` work for ANY id. So TermDesk discovers ids from rollout
 * FILENAMES — no JSONL content is parsed — and then asks the kernel for the
 * metadata and transcript anyway. The phone's list therefore stays a single
 * list whose every entry is openable and resumable.
 *
 * `rollout-<timestamp>-<uuid>.jsonl` is the naming convention; files whose
 * trailing id is a child (subagent) thread are dropped later, when the kernel
 * reports `parentThreadId` for them.
 */
export function discoverThreadIdsFromDisk({ root, limit = 80 } = {}) {
  const found = [];
  const walk = (dir, depth) => {
    if (depth > 4) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) { walk(full, depth + 1); continue; }
      if (!entry.name.endsWith('.jsonl')) continue;
      const match = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$/.exec(entry.name);
      if (!match) continue;
      let mtime = 0;
      try { mtime = fs.statSync(full).mtimeMs; } catch { /* keep 0 */ }
      found.push({ id: match[1], mtime });
    }
  };
  walk(root, 0);
  const newest = new Map();
  for (const item of found) {
    const prev = newest.get(item.id);
    if (!prev || item.mtime > prev) newest.set(item.id, item.mtime);
  }
  return [...newest.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([id]) => id);
}

export class CodexAppServer extends EventEmitter {
  constructor({ bin = findCodex(), args = ['app-server', '--listen', 'stdio://'], cwd = null, log = console.log } = {}) {
    super();
    this.bin = bin;
    this.args = args;
    this.cwd = cwd;
    this.log = log;
    this.child = null;
    this.starting = null;
    this.nextId = 1;
    this.pending = new Map();
    this.stderrTail = '';
    this.buffer = '';
    this.disposed = false;
    /**
     * threadId -> turnId of the turn currently in flight.
     *
     * `turn/interrupt` requires BOTH ids (verified: passing null is rejected with
     * "invalid type: null, expected a string"), and the phone only knows the
     * thread, so the adapter remembers the active turn for it.
     */
    this.activeTurns = new Map();
  }

  /** Spawn once; concurrent callers share the same handshake. */
  async ensureStarted() {
    if (this.disposed) throw new Error('codex adapter disposed');
    if (this.child && this.child.exitCode === null) return this;
    if (this.starting) return this.starting;
    this.starting = (async () => {
      // Prefer the resolved absolute path so a stripped PATH (scheduled task,
      // service, hidden shell) cannot break the launch. Fall back to a shell
      // only when we are left with a bare command name.
      const shell = process.platform === 'win32' && !fs.existsSync(this.bin);
      const child = spawn(this.bin, this.args, {
        cwd: this.cwd ?? undefined,
        stdio: ['pipe', 'pipe', 'pipe'],
        shell,
      });
      this.child = child;
      this.buffer = '';
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (chunk) => this.onStdout(chunk));
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (chunk) => {
        // Keep a tail for diagnostics; never echo credentials.
        this.stderrTail = (this.stderrTail + chunk).slice(-4000);
      });
      // An unhandled "error" event would take the whole agent process down, so
      // it is always handled: the failure is reported to callers instead.
      child.on('error', (err) => {
        this.log(`[codex] app-server 启动/运行失败：${err?.message ?? err}`);
        this.child = null;
        for (const [, p] of this.pending) p.reject(new Error(`codex app-server 无法运行：${err?.message ?? err}`));
        this.pending.clear();
        this.emit('exit', -1);
      });
      child.on('exit', (code) => {
        this.child = null;
        for (const [, p] of this.pending) p.reject(new Error(`codex app-server exited (${code})`));
        this.pending.clear();
        this.emit('exit', code);
      });
      await this.request('initialize', {
        clientInfo: { name: 'termdesk-pc-agent', title: 'TermDesk', version: '0.1' },
        capabilities: {},
      });
      this.emit('ready');
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
      // Response to our own request.
      if (message.id !== undefined && message.method === undefined) {
        const pending = this.pending.get(message.id);
        if (!pending) continue;
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        if (message.error) pending.reject(new Error(`${message.error.message ?? 'rpc error'} (${message.error.code ?? ''})`));
        else pending.resolve(message.result);
        continue;
      }
      // Server -> client request (approvals & friends). Must be answered.
      if (message.id !== undefined && message.method) {
        this.answerServerRequest(message);
        continue;
      }
      if (message.method) {
        const params = message.params ?? {};
        if (message.method === 'turn/started' && params.turn?.id && params.threadId) this.activeTurns.set(params.threadId, params.turn.id);
        if (message.method === 'turn/completed' && params.threadId && params.turn?.id === this.activeTurns.get(params.threadId)) this.activeTurns.delete(params.threadId);
        this.emit('notification', message.method, params);
      }
    }
  }

  /**
   * We do not implement interactive approvals. Surface the request to the phone
   * and answer with an explicit refusal, so the turn fails visibly instead of
   * hanging. The PC's own Codex config (sandbox/approval policy) still applies.
   */
  answerServerRequest(message) {
    this.emit('serverRequest', message.method, message.params ?? {});
    this.send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: `termdesk does not implement ${message.method}` } });
  }

  send(value) {
    const child = this.child;
    if (!child || child.exitCode !== null) return false;
    child.stdin.write(JSON.stringify(value) + '\n');
    return true;
  }

  request(method, params, { timeout = REQUEST_TIMEOUT_MS } = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`codex ${method} timed out`));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer, method });
      if (!this.send({ jsonrpc: '2.0', id, method, params })) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new Error('codex app-server is not running'));
      }
    });
  }

  async call(method, params, options) {
    await this.ensureStarted();
    return this.request(method, params, options);
  }

  // ---- typed surface (the only thing callers should use) ----

  listThreads({ limit = 40, cursor, cwd, searchTerm, archived = false, sortKey, sortDirection } = {}) {
    const params = { limit, archived };
    if (cursor) params.cursor = cursor;
    if (cwd) params.cwd = cwd;
    if (searchTerm) params.searchTerm = searchTerm;
    if (sortKey) params.sortKey = sortKey;
    if (sortDirection) params.sortDirection = sortDirection;
    return this.call('thread/list', params);
  }

  async readThread(threadId, { includeTurns = true } = {}) {
    const result = await this.call('thread/read', { threadId, includeTurns });
    return result?.thread ?? null;
  }

  startThread(params = {}) { return this.call('thread/start', params); }

  resumeThread(threadId, params = {}) { return this.call('thread/resume', { threadId, ...params }); }

  async startTurn(threadId, text, params = {}) {
    const result = await this.call('turn/start', { threadId, input: [{ type: 'text', text }], ...params });
    if (result?.turn?.id) this.activeTurns.set(threadId, result.turn.id);
    return result;
  }

  /** The turn currently in flight for this thread, if any. */
  activeTurnId(threadId) { return this.activeTurns.get(threadId) ?? null; }

  /**
   * Interrupt the in-flight turn. `turnId` may be omitted: the adapter falls
   * back to the turn it saw start most recently for that thread.
   */
  /** Native thread ids this app-server currently holds open. */
  async listLoadedThreads() {
    const result = await this.call('thread/loaded/list', {});
    const list = result?.threadIds ?? result?.ids ?? result?.data ?? [];
    return Array.isArray(list) ? list.map((x) => (typeof x === 'string' ? x : x?.threadId ?? x?.id)).filter(Boolean) : [];
  }

  async interruptTurn(threadId, turnId = null) {
    const id = turnId ?? this.activeTurns.get(threadId);
    if (!id) throw new Error('no active turn to interrupt for this thread');
    return this.call('turn/interrupt', { threadId, turnId: id });
  }

  dispose() {
    this.disposed = true;
    for (const [, p] of this.pending) p.reject(new Error('codex adapter disposed'));
    this.pending.clear();
    try { this.child?.kill(); } catch { /* already gone */ }
    this.child = null;
  }
}

