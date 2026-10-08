/**
 * One live conversation: one engine runtime plus its transcript.
 *
 * This module owns *session state* and nothing else — the event log, the command
 * lines the conversation ran, and the shape the phone reads (`summary` / `detail`).
 * Which engine backs a conversation, how a turn is started and how kernel
 * notifications are routed all live in `manager.js` and the per-kernel adapters,
 * so this file never reaches back into the manager.
 *
 * Kept separate on purpose: the manager is ~1700 lines of engine orchestration,
 * and mixing "what this conversation currently is" into it made both harder to
 * read and impossible to test on their own.
 */

/**
 * How much of one command's output the phone's copy keeps.
 *
 * The screen shows a few hundred lines; the cap is here so that a command which
 * prints forever cannot grow this process's memory through the transcript copy.
 * The tail is what is kept — that is where the answer usually is.
 */
const MAX_TERMINAL_OUTPUT_CHARS = 64 * 1024;

/**
 * The transcript is bounded for the same reason the terminal output is: a long
 * conversation must not be able to grow this process without limit. The oldest
 * events are dropped first, and `seq` keeps increasing, so an incremental client
 * can still tell "newer" from "older" after a drop.
 */
const MAX_EVENTS_PER_CHAT = 1200;

export class Chat {
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

/**
 * The per-chat JSON-RPC request helper.
 *
 * Attached to the prototype rather than written inside the class body: the DSH
 * runtime speaks newline-delimited JSON-RPC over the child's stdin, and keeping
 * that detail out of the state definition makes the class readable. It stays
 * here (not in the manager) because it only ever touches this chat's own child
 * and pending map.
 */
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
