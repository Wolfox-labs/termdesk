/**
 * Terminals that belong to this machine, handed to ACP kernels.
 *
 * ACP puts the terminal on the *client* side: an agent that wants to run a
 * command asks us (`terminal/create`), then polls `terminal/output`, waits with
 * `terminal/wait_for_exit`, and finally `terminal/release`s it. That is the
 * arrangement VS Code + Copilot uses, and it is why the phone can show — and
 * drive — the command line a conversation is using: the process is ours.
 *
 * What this is not: a PTY. Windows has no PTY without a native module and this
 * agent deliberately has none, so these are pipes. Commands run, output streams,
 * exit codes arrive, and stdin can be written (which is what "接管" needs in
 * practice); a program that insists on a real terminal will take its
 * non-interactive path. The ACP spec does not require a PTY either — it asks for
 * a terminal *session*, and this is one.
 *
 * Buffers are capped (the spec's `outputByteLimit`, defaulting to 256 KB) and
 * truncated from the beginning at a character boundary, exactly as the spec
 * describes, so a runaway command cannot eat this machine's memory.
 */
import { spawn as spawnProcess } from 'node:child_process';

/** Per the spec: retain at most this unless the agent asks for less. */
const DEFAULT_OUTPUT_BYTE_LIMIT = 256 * 1024;

/** How long a finished terminal stays readable before it is reaped. */
const FINISHED_TTL_MS = 30 * 60 * 1000;

/**
 * A byte slice that starts on a character boundary.
 *
 * Cutting a UTF-8 sequence in half would put U+FFFD in front of the output the
 * agent reads; skipping the continuation bytes keeps the text valid.
 */
function decodeFromBoundary(buffer) {
  let start = 0;
  while (start < buffer.length && (buffer[start] & 0b1100_0000) === 0b1000_0000) start += 1;
  return buffer.subarray(start).toString('utf8');
}

class AcpTerminal {
  constructor(id, { sessionId, command, args, cwd, env, outputByteLimit, onEvent }) {
    this.id = id;
    this.sessionId = sessionId;
    this.command = command;
    this.args = args;
    this.cwd = cwd ?? null;
    this.onEvent = onEvent;
    this.limit = Number.isFinite(outputByteLimit) && outputByteLimit > 0
      ? Math.floor(outputByteLimit)
      : DEFAULT_OUTPUT_BYTE_LIMIT;

    this.chunks = [];
    this.bytes = 0;
    this.truncated = false;
    this.exitStatus = null;
    this.startedAt = Date.now();
    this.finishedAt = null;
    this.exitWaiters = [];
    this.closed = false;

    this.child = spawnProcess(command, args, {
      cwd: cwd ?? undefined,
      env: mergeEnv(env),
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });

    this.child.stdout.on('data', (chunk) => this.append(chunk));
    this.child.stderr.on('data', (chunk) => this.append(chunk));
    this.child.on('error', (err) => {
      // A command that cannot even start is reported as output plus a non-zero
      // exit, because that is what the agent can act on.
      this.append(Buffer.from(`${err?.message ?? String(err)}\n`, 'utf8'));
      this.finish({ exitCode: 127, signal: null });
    });
    this.child.on('exit', (code, signal) => this.finish({ exitCode: code, signal: signal ?? null }));

    // The session id is on the event itself, not only inside the summary: the
    // chat manager routes by it, and a nested id it has to dig for is a bug
    // waiting to happen (it was: the creation event never reached its chat).
    this.onEvent({ type: 'created', sessionId: this.sessionId, terminal: this.summary() });
  }

  append(chunk) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk), 'utf8');
    this.chunks.push(buf);
    this.bytes += buf.length;
    let dropped = false;
    while (this.bytes > this.limit && this.chunks.length > 0) {
      const first = this.chunks[0];
      const excess = this.bytes - this.limit;
      if (first.length <= excess) {
        this.chunks.shift();
        this.bytes -= first.length;
      } else {
        // Trim inside the first chunk; the leading partial character is removed
        // when the buffer is decoded.
        const trimmed = first.subarray(excess);
        this.chunks[0] = trimmed;
        this.bytes -= excess;
      }
      dropped = true;
    }
    if (dropped) this.truncated = true;
    this.onEvent({ type: 'output', terminalId: this.id, sessionId: this.sessionId, chunk: buf.toString('utf8') });
  }

  finish(status) {
    if (this.exitStatus) return;
    this.exitStatus = status;
    this.finishedAt = Date.now();
    for (const resolve of this.exitWaiters) resolve(status);
    this.exitWaiters = [];
    this.onEvent({ type: 'exited', terminalId: this.id, sessionId: this.sessionId, ...status });
  }

  read() {
    const buffer = Buffer.concat(this.chunks, this.bytes);
    return { output: decodeFromBoundary(buffer), truncated: this.truncated, exitStatus: this.exitStatus };
  }

  waitForExit() {
    if (this.exitStatus) return Promise.resolve(this.exitStatus);
    return new Promise((resolve) => this.exitWaiters.push(resolve));
  }

  write(data) {
    if (!this.child?.stdin || this.child.stdin.destroyed) return false;
    this.child.stdin.write(data);
    this.onEvent({ type: 'input', terminalId: this.id, sessionId: this.sessionId, data });
    return true;
  }

  kill() {
    if (!this.child || this.exitStatus) return;
    try {
      this.child.kill();
    } catch {
      // Already gone: the exit handler settles the record either way.
    }
  }

  release() {
    this.closed = true;
    this.kill();
  }

  summary() {
    return {
      id: this.id,
      sessionId: this.sessionId,
      command: [this.command, ...(this.args ?? [])].join(' ').trim(),
      cwd: this.cwd,
      state: this.exitStatus ? 'exited' : 'running',
      exitCode: this.exitStatus?.exitCode ?? null,
      startedAt: this.startedAt,
      bytes: this.bytes,
      truncated: this.truncated,
    };
  }
}

/** ACP sends env as `[{ name, value }]`; the process wants a plain object. */
function mergeEnv(env) {
  const base = { ...process.env };
  if (!Array.isArray(env)) return base;
  for (const entry of env) {
    if (!entry || typeof entry.name !== 'string') continue;
    if (entry.value === null || entry.value === undefined) delete base[entry.name];
    else base[entry.name] = String(entry.value);
  }
  return base;
}

/**
 * Every terminal any ACP kernel on this agent asked for.
 *
 * Kept per kernel instance (see `acp.js`) so one kernel's session ids can never
 * address another kernel's terminals, and so releasing the kernel disposes
 * exactly its own processes.
 */
export class AcpTerminals {
  constructor({ onEvent = () => {} } = {}) {
    this.terminals = new Map();
    this.onEvent = onEvent;
    this.counter = 0;
  }

  create(params = {}) {
    const command = params.command;
    if (typeof command !== 'string' || !command.trim()) {
      throw new Error('terminal/create 缺少 command');
    }
    const args = Array.isArray(params.args) ? params.args.map(String) : [];
    this.counter += 1;
    const id = `t${this.counter}`;
    const terminal = new AcpTerminal(id, {
      sessionId: params.sessionId ?? null,
      command,
      args,
      cwd: params.cwd ?? null,
      env: params.env,
      outputByteLimit: params.outputByteLimit,
      onEvent: this.onEvent,
    });
    this.terminals.set(id, terminal);
    this.reap();
    return { terminalId: id };
  }

  output(params = {}) {
    const terminal = this.terminals.get(params.terminalId);
    // The spec's answer for an unknown id is an error; inventing empty output
    // would let an agent believe a command ran when nothing did.
    if (!terminal) throw new Error(`未知的终端：${params.terminalId}`);
    return terminal.read();
  }

  waitForExit(params = {}) {
    const terminal = this.terminals.get(params.terminalId);
    if (!terminal) throw new Error(`未知的终端：${params.terminalId}`);
    return terminal.waitForExit().then((status) => ({
      exitCode: status.exitCode ?? null,
      signal: status.signal ?? null,
    }));
  }

  kill(params = {}) {
    this.terminals.get(params.terminalId)?.kill();
    return {};
  }

  release(params = {}) {
    const terminal = this.terminals.get(params.terminalId);
    terminal?.release();
    this.terminals.delete(params.terminalId);
    this.onEvent({ type: 'released', terminalId: params.terminalId, sessionId: terminal?.sessionId ?? null });
    return {};
  }

  /** TermDesk's own extension: the phone typing into a terminal we own. */
  write(terminalId, data) {
    const terminal = this.terminals.get(terminalId);
    if (!terminal) throw new Error(`未知的终端：${terminalId}`);
    return terminal.write(String(data ?? ''));
  }

  /** Everything still known, for the phone's terminal list. */
  list() {
    return [...this.terminals.values()].map((t) => t.summary());
  }

  /** Drop finished terminals nobody is going to ask about again. */
  reap(now = Date.now()) {
    for (const [id, terminal] of this.terminals) {
      if (terminal.finishedAt && now - terminal.finishedAt > FINISHED_TTL_MS) {
        this.terminals.delete(id);
      }
    }
  }

  dispose() {
    for (const [, terminal] of this.terminals) terminal.release();
    this.terminals.clear();
  }
}

export const ACP_TERMINAL_METHODS = new Set([
  'terminal/create',
  'terminal/output',
  'terminal/wait_for_exit',
  'terminal/kill',
  'terminal/release',
]);
