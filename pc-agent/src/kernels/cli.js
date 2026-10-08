/**
 * CLI shim adapter - ONE adapter for every kernel that only has a command line.
 *
 * QoderWork and Command Code have no ACP server: they are CLIs with a
 * `--print`-style non-interactive mode, a session id in their JSON output, and
 * a `--resume`/`--session` flag to continue a session. Rather than write a
 * bespoke integration per CLI, the differences live in a manifest (see
 * `shimSpec()` in kernels/registry.js) and this file drives all of them:
 *
 *   listSessions()  the CLI's own session index, when it has one
 *   prompt()        one turn: spawn, stream NDJSON, capture the session id
 *   cancel()        kill the process tree (a CLI turn has no protocol-level stop)
 *
 * Updates are emitted in the SAME shape ACP uses (`sessionUpdate:
 * 'agent_message_chunk'`), so the chat pipeline, the transcript and the phone
 * have one path and not two. The streamed chunks accumulate, exactly as they do
 * for ACP kernels.
 *
 * What it does NOT pretend to do:
 *   - replay a transcript. A CLI usually cannot hand back a past conversation's
 *     body, so `replay` is false and opening such a session says so instead of
 *     showing an empty conversation.
 *   - switch models, UNLESS the manifest names the flag that does it (`modelFlag`).
 *     QoderWork has `-m/--model`, so it is real there; a CLI without one throws
 *     rather than silently ignoring the request.
 *   - ask for permission. A CLI decides on its own; there is no protocol for it,
 *     so nothing is invented here.
 *
 * The manifest paths below were checked against QoderWork's real output (see
 * `parseStreamLine` and tools/cli-shim-test.js, which replays lines captured from
 * `qoderclicn -p -o stream-json`, v1.1.26).
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';

import { killProcessTree } from '../engines.js';
import { kernelEnv, rememberSpawn, forgetSpawn } from '../spawnledger.js';

const MAX_PARTIAL = 4000;

/**
 * Dotted-path lookup, with numeric segments for arrays:
 *
 *   valueAt({ a: { b: 1 } }, 'a.b')                            -> 1
 *   valueAt({ message: { content: [{ text: 'hi' }] } },
 *           'message.content.0.text')                          -> 'hi'
 *
 * Numeric segments matter because this is exactly how QoderWork's CLI reports an
 * answer: `message.content[0].text`.
 */
export function valueAt(object, path) {
  if (!path || !object || typeof object !== 'object') return undefined;
  let current = object;
  for (const part of String(path).split('.')) {
    const index = Number(part);
    if (Array.isArray(current) && Number.isInteger(index)) {
      current = current[index];
      continue;
    }
    if (current == null || typeof current !== 'object') return undefined;
    current = current[part];
  }
  return current;
}

/**
 * First non-empty string at any of the given paths. An array of strings (or of
 * `{ text }` blocks, which is what a CLI emits) is joined.
 */
export function firstString(object, paths) {
  if (!paths) return '';
  const list = Array.isArray(paths) ? paths : [paths];
  for (const path of list) {
    const value = valueAt(object, path);
    if (typeof value === 'string' && value.trim()) return value;
    if (Array.isArray(value)) {
      const joined = value
        .map((item) => (typeof item === 'string' ? item : (item?.text ?? '')))
        .join('');
      if (joined.trim()) return joined;
    }
  }
  return '';
}

/**
 * One NDJSON line from a CLI, in the shim's own vocabulary.
 *
 * Kept pure and exported so the mapping can be checked against lines captured
 * from the real CLI - no process, no model, no cost.
 */
export function parseStreamLine(line, manifest = {}) {
  let parsed = null;
  try {
    parsed = JSON.parse(line);
  } catch {
    // Not JSON: still output the user may need to see (a banner, a warning).
    return { raw: null, text: line, sessionId: null, final: '', error: '', errorText: '' };
  }
  return {
    raw: parsed,
    text: firstString(parsed, manifest.textPaths ?? manifest.text),
    // Some CLIs print one authoritative result line at the end instead of (or as
    // well as) streaming. Emitting both would show the answer twice - the exact
    // duplication this project already fought once - so `final` is only used
    // when nothing streamed.
    final: firstString(parsed, manifest.resultPaths),
    sessionId: firstString(parsed, manifest.sessionId) || null,
    error: firstString(parsed, manifest.errorPaths),
    errorText: firstString(parsed, manifest.errorTextPaths),
  };
}

export class CliKernel {
  /**
   * @param {object} options
   * @param {string} options.id kernel id, as the registry knows it
   * @param {string} options.bin the CLI to run
   * @param {string[]} [options.preArgs] arguments before every CLI invocation
   * @param {object} options.manifest everything CLI-specific, from the registry
   * @param {(line: string) => void} [options.log]
   */
  constructor({ id, bin, preArgs, manifest, log = console.log } = {}) {
    this.id = id;
    this.bin = bin;
    /**
     * A node-hosted CLI is `node <entry> <args>`; without this the flags would be
     * handed to node itself ("bad option: --output-format"). The registry already
     * resolves that shape, so it is passed through rather than guessed.
     */
    this.preArgs = Array.isArray(preArgs) ? preArgs : [];
    this.manifest = manifest ?? {};
    this.log = log;
    /** Session ids this adapter has seen. */
    this.sessions = new Set();
    this.child = null;
    this.running = null;
    this.stderrTail = '';
    this.cwd = null;
    this.listeners = new Map();
    /** A CLI cannot replay a transcript; the chat pipeline quotes this. */
    this.canReplay = Boolean(this.manifest.replay);
  }

  on(event, handler) {
    if (!this.listeners.has(event)) this.listeners.set(event, []);
    this.listeners.get(event).push(handler);
  }

  emit(event, ...args) {
    for (const handler of this.listeners.get(event) ?? []) {
      try { handler(...args); } catch (err) { this.log(`[cli:${this.id}] 监听器出错：${err?.message ?? err}`); }
    }
  }

  /** Nothing to start: each turn is its own process. Kept for interface parity. */
  async ensureStarted() {
    if (!fs.existsSync(this.bin)) throw new Error(`找不到 CLI：${this.bin}`);
  }

  sessionSupport() {
    const m = this.manifest;
    return {
      loadSession: Boolean(m.replay),
      list: Boolean(m.listArgs?.length),
      resume: m.resume !== false,
      fork: false,
      close: false,
      image: false,
    };
  }

  /**
   * A CLI names the session, not the client: there is nothing to create until a
   * turn runs. Returning '' says exactly that, and the real id is adopted from
   * the turn's own output.
   */
  async newSession({ cwd } = {}) {
    this.cwd = cwd ?? null;
    return '';
  }

  /** The CLI's session index, mapped to the shape the chat pipeline expects. */
  async listSessions({ cwd } = {}) {
    const args = this.manifest.listArgs ?? [];
    // `supported` is what the chat pipeline branches on: a CLI with no index of
    // its own must say so, or the phone would show an empty history as if it were
    // real.
    if (!args.length || this.manifest.listParsed === false) return { supported: false, sessions: [] };
    const { stdout } = await this.run(args, null, cwd ?? this.cwd);
    const sessions = [];
    for (const line of String(stdout).split('\n')) {
      const text = line.trim();
      if (!text) continue;
      const parsed = parseStreamLine(text, this.manifest);
      const sessionId = parsed.sessionId;
      if (!sessionId) continue;
      this.sessions.add(String(sessionId));
      const raw = parsed.raw ?? {};
      sessions.push({
        sessionId: String(sessionId),
        cwd: firstString(raw, this.manifest.sessionCwd) || cwd || this.cwd || null,
        title: firstString(raw, this.manifest.sessionTitle) || null,
        updatedAt: firstString(raw, this.manifest.sessionUpdatedAt) || null,
      });
    }
    return { supported: true, sessions };
  }

  /**
   * Attach to a past session. With no transcript to replay this resolves
   * immediately: the next prompt continues it through the CLI's own `--resume`.
   */
  async loadSession(sessionId) {
    if (!sessionId) throw new Error('缺少会话 id');
    this.sessions.add(String(sessionId));
  }

  /** The CLI's model list, when it can be asked for one without a turn. */
  availableModels() {
    return { current: null, models: [] };
  }

  /**
   * Switching models is only real when the manifest names the flag. QoderWork's
   * CLI has `-m/--model`, so it works; for a CLI without one this throws instead
   * of pretending.
   */
  async setModel(_sessionId, modelId) {
    const flag = this.manifest.modelFlag;
    if (!flag) {
      throw new Error(`${this.id} 的 CLI 没有模型开关，TermDesk 不会假装切换了模型`);
    }
    if (!modelId) throw new Error('缺少模型 id');
    this.model = modelId;
    return { applied: modelId };
  }

  /**
   * One turn. Resolves when the CLI exits; the answer streams out as it arrives.
   * @returns {Promise<{stopReason: string, sessionId: string|null}>}
   */
  prompt(sessionId, text) {
    if (this.child) {
      // One turn at a time per CLI: a second concurrent process would make the
      // session id ambiguous and the transcript wrong.
      return Promise.reject(new Error(`${this.id} 同一时间只能跑一轮回复`));
    }
    const m = this.manifest;
    const args = [...(sessionId ? (m.resumeArgs ?? []) : (m.newArgs ?? []))];
    if (sessionId) args.push(String(sessionId));
    if (m.modelFlag && this.model) args.push(m.modelFlag, this.model);
    const viaStdin = m.prompt === 'stdin';
    if (!viaStdin) args.push(text);

    return new Promise((resolve, reject) => {
      let child;
      try {
        child = spawn(this.bin, [...this.preArgs, ...args], {
          cwd: this.cwd ?? undefined,
          windowsHide: true,
          env: kernelEnv(),
        });
        rememberSpawn(child.pid, `cli:${this.id}`);
        child.once('exit', () => forgetSpawn(child.pid));
      } catch (err) {
        reject(new Error(`无法启动 ${this.id}：${err?.message ?? err}`));
        return;
      }
      this.child = child;
      let session = sessionId ? String(sessionId) : null;
      this.running = session;
      this.stderrTail = '';
      let buffer = '';
      let pieces = 0;
      let finalText = '';
      let errorText = '';

      const stream = (chunk) => {
        this.emit('update', session, {
          sessionUpdate: 'agent_message_chunk',
          content: { type: 'text', text: chunk },
        });
      };

      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (data) => {
        buffer += data;
        let index;
        while ((index = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, index).trim();
          buffer = buffer.slice(index + 1);
          if (!line) continue;
          const parsed = parseStreamLine(line, m);
          if (parsed.sessionId && !session) {
            session = parsed.sessionId;
            this.running = session;
            this.sessions.add(session);
          }
          if (parsed.error && !errorText) errorText = parsed.errorText || parsed.error;
          if (parsed.final) finalText = parsed.final;
          if (parsed.text) {
            stream(parsed.text);
            pieces += 1;
          }
        }
      });
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (data) => {
        this.stderrTail = (this.stderrTail + data).slice(-MAX_PARTIAL);
      });
      child.on('error', (err) => {
        this.child = null;
        this.running = null;
        reject(new Error(`无法运行 ${this.id}：${err?.message ?? err}`));
      });
      child.on('close', (code) => {
        this.child = null;
        this.running = null;
        if (buffer.trim()) {
          const parsed = parseStreamLine(buffer.trim(), m);
          if (parsed.text) { stream(parsed.text); pieces += 1; }
          else { stream(buffer.trim()); pieces += 1; }
        }
        // A CLI that only prints a final line still gets its answer shown - once.
        if (pieces === 0 && finalText) {
          stream(finalText);
          pieces += 1;
        }
        if (code !== 0) {
          const tail = (this.stderrTail || errorText).replace(/\s+/g, ' ').trim().slice(-300);
          reject(new Error(`${this.id} 退出码 ${code}${tail ? `：${tail}` : ''}`));
          return;
        }
        // Exit code 0 with an error in the stream is a refusal, not an answer:
        // QoderWork reports "not logged in" exactly this way.
        if (pieces === 0 && errorText) {
          reject(new Error(`${this.id}：${errorText}`));
          return;
        }
        resolve({ stopReason: pieces ? 'end_turn' : 'empty', sessionId: session });
      });

      if (viaStdin) child.stdin.end(text);
      else child.stdin.end();
    });
  }

  /** Stop the in-flight turn. A CLI turn is a process, so it is the process. */
  cancel() {
    const child = this.child;
    if (!child) return false;
    killProcessTree(child);
    return true;
  }

  dispose() {
    this.cancel();
    this.listeners.clear();
  }

  /** Run a short-lived command and collect its output (used for the index). */
  run(args, input, cwd) {
    return new Promise((resolve, reject) => {
      // A short-lived command (the session index) is recorded too: a crash can
      // orphan it just as easily as a long-lived kernel.
      const child = spawn(this.bin, [...this.preArgs, ...args], {
        cwd: cwd ?? undefined,
        windowsHide: true,
        env: kernelEnv(),
      });
      rememberSpawn(child.pid, `cli-run:${this.id}`);
      child.once('exit', () => forgetSpawn(child.pid));
      let stdout = '';
      let stderr = '';
      child.stdout.on('data', (d) => { stdout += d; });
      child.stderr.on('data', (d) => { stderr += d; });
      child.on('error', (err) => reject(new Error(`无法运行 ${this.id}：${err?.message ?? err}`)));
      child.on('close', (code) => {
        if (code !== 0) {
          reject(new Error(`${this.id} 列出会话失败（退出码 ${code}）${stderr ? `：${stderr.slice(-200)}` : ''}`));
          return;
        }
        resolve({ stdout, stderr });
      });
      if (input != null) child.stdin.end(input);
    });
  }
}
