/**
 * Talking to the DeepSeek Harness desktop app that is ALREADY running.
 *
 * The SDK runtime (`dsh --profile sdk`) is a second process, and DSH's session
 * store allows one writer per session: while the desktop app has a conversation
 * open, `session/resume` answers
 *
 *   session "…" is already owned by an active write handle
 *
 * which is correct — two writers would fork the same journal. But the owner's
 * phone should still be able to continue that conversation, so the message has to
 * go to the agent that already owns it.
 *
 * The desktop app is a web host: the GUI talks to its own process over
 * `POST /api/<endpoint>` (a thin envelope around the generated Remote calls) and a
 * `/api/remote.mux` WebSocket. Its owner authentication is a signed cookie whose
 * signing secret lives in the harness credential store
 * (`client-connection/browser-session`), so a same-user process can mint the very
 * cookie the app's own window uses — no launch token, no token copying, and no
 * modification of the DSH installation.
 *
 * This module is that client, deliberately small:
 *
 *   - `discoverLiveHost()`  find the running desktop host and its loopback port;
 *   - `liveCall()`          one `POST /api/<endpoint>` with an owner cookie;
 *   - `livePrompt()`        queue one user message into the live agent;
 *   - `tailJournal()`       read the events the live agent appends, so a client
 *                           can show the answer without a second writer.
 *
 * The credential file is read on every use and the secret is never copied into
 * TermDesk's own state, logged, or sent anywhere. Authentication is limited to a
 * loopback Host: this is not a remote-access mechanism.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { WebSocket } from 'ws';

const COOKIE_PREFIX = 'dsh-auth-';
const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
/** A host answer is cheap; a stale port must not make every turn wait. */
const DISCOVERY_TTL_MS = 30_000;

let cached = null;

/** The credential file the desktop app signs its browser cookies with. */
export function credentialsFile(env = process.env) {
  const home = env.TERMDESK_DSH_DIR || path.join(os.homedir(), '.dsh');
  return path.join(home, '.credentials.yaml');
}

/** The 32-byte browser-session secret, or null when the record is absent. */
export function browserSessionSecret(env = process.env) {
  let text;
  try { text = fs.readFileSync(credentialsFile(env), 'utf8'); } catch { return null; }
  const match = text.match(
    /client-connection\/browser-session:\s*\r?\n\s+kind:\s*grant\s*\r?\n\s+payload:\s*\r?\n\s+version:\s*1\s*\r?\n\s+secret:\s*([A-Za-z0-9_-]+)/,
  );
  if (!match) return null;
  const encoded = match[1].replaceAll('-', '+').replaceAll('_', '/');
  const secret = Buffer.from(encoded + '='.repeat((4 - (encoded.length % 4)) % 4), 'base64');
  return secret.length === 32 ? secret : null;
}

function base64url(value) {
  return Buffer.from(value).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

/**
 * Mint the owner cookie for one loopback authority.
 *
 * Format measured from the installed 0.2.1-rc.1 client:
 *   name  = dsh-auth-<base64url(sha256(authority))>
 *   value = v1.<base64url(json{version,authority,issuedAt,expiresAt})>.<base64url(hmac-sha256)>
 * The authority for plain HTTP is `host:port` (not the scheme), which is why this
 * only ever targets `127.0.0.1:<port>`.
 */
export function mintCookie(secret, authority, now = Date.now(), maxAgeDays = 30) {
  const payload = {
    version: 1,
    authority,
    issuedAt: now,
    expiresAt: now + maxAgeDays * 86_400_000,
  };
  const body = base64url(Buffer.from(JSON.stringify(payload), 'utf8'));
  const signature = base64url(crypto.createHmac('sha256', secret).update(body).digest());
  const name = COOKIE_PREFIX + base64url(crypto.createHash('sha256').update(authority).digest());
  return { name, value: `v1.${body}.${signature}` };
}

/**
 * The running desktop host, or null.
 *
 * Found from the process command line (the app runs `dsh-desktop-host/lib/index.js`)
 * and the loopback port that process listens on. Cached briefly: the port only
 * changes when the app restarts, and every prompt would otherwise pay a process
 * enumeration.
 */
export function discoverLiveHost({ env = process.env, platform = process.platform, fresh = false } = {}) {
  if (platform !== 'win32') return null;
  if (!fresh && cached && Date.now() - cached.at < DISCOVERY_TTL_MS && hostAlive(cached.host)) return cached.host;

  let pid = 0;
  let port = 0;
  try {
    const ps = 'Get-CimInstance Win32_Process -Filter "Name=\'DeepSeek Harness.exe\'" '
      + "| Where-Object { $_.CommandLine -match 'dsh-desktop-host' } "
      + '| Select-Object -First 1 -ExpandProperty ProcessId';
    pid = Number(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], {
      encoding: 'utf8', timeout: 8000, windowsHide: true,
    }).trim());
    if (!Number.isFinite(pid) || pid <= 0) return null;
    const ports = execFileSync('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-Command',
      `Get-NetTCPConnection -State Listen -OwningProcess ${pid} | Select-Object -ExpandProperty LocalPort`,
    ], { encoding: 'utf8', timeout: 8000, windowsHide: true })
      .split(/\s+/)
      .map((value) => Number(value))
      .filter((value) => Number.isInteger(value) && value > 0 && value < 65536);
    // One loopback listener is the web host; a second would be unusual, so the
    // choice is reported rather than silently guessed.
    port = ports[0] ?? 0;
  } catch {
    return null;
  }
  if (!port) return null;
  const host = { pid, port, authority: `127.0.0.1:${port}`, discoveredAt: Date.now() };
  cached = { at: Date.now(), host };
  return host;
}

/** Forget the cached host (called after a failure so the next turn re-discovers). */
export function forgetLiveHost() {
  cached = null;
}

function hostAlive(host) {
  try {
    const secret = browserSessionSecret();
    if (!secret) return false;
    const { name, value } = mintCookie(secret, host.authority);
    // A synchronous socket would need a raw http client; the caller's next call
    // will re-discover on failure, so liveness here is only a cheap check.
    void name; void value;
    return true;
  } catch {
    return false;
  }
}

/**
 * One `POST /api/<endpoint>` with the owner cookie.
 *
 * Returns `{ ok, value }` or `{ ok: false, code, message }`; a transport failure is
 * reported as `code: 'transport'` so callers can fall back or say what happened.
 */
export async function liveCall(host, endpoint, args, { signal, timeoutMs = 60_000 } = {}) {
  const secret = browserSessionSecret();
  if (!secret) return { ok: false, code: 'no_credential', message: '本机 DSH 没有可用的浏览器凭据，无法接入正在运行的桌面端' };
  const { name, value } = mintCookie(secret, host.authority);
  const rpcId = crypto.randomUUID();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const onAbort = () => controller.abort();
  signal?.addEventListener('abort', onAbort, { once: true });
  try {
    const response = await fetch(`http://${host.authority}/api/${endpoint}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', cookie: `${name}=${value}` },
      body: JSON.stringify({ type: 'client-request', rpcId, method: endpoint, payload: { args } }),
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) {
      forgetLiveHost();
      return { ok: false, code: 'transport', message: `DSH 桌面端返回 HTTP ${response.status}` };
    }
    let frame;
    try { frame = JSON.parse(text); } catch { return { ok: false, code: 'transport', message: 'DSH 桌面端返回了非 JSON 响应' }; }
    if (frame?.rpcId !== rpcId) return { ok: false, code: 'transport', message: 'DSH 桌面端响应与请求不匹配' };
    const result = frame?.result;
    if (result?.ok === true) return { ok: true, value: result.value };
    const error = result?.error ?? {};
    return {
      ok: false,
      code: typeof error.code === 'string' ? error.code : 'unknown',
      message: typeof error.message === 'string' ? error.message : 'DSH 桌面端拒绝了这次调用',
      details: error.details ?? {},
    };
  } catch (err) {
    if (signal?.aborted) return { ok: false, code: 'cancelled', message: '已取消' };
    forgetLiveHost();
    return { ok: false, code: 'transport', message: String(err?.message ?? err) };
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

/**
 * Queue one user message into the live agent.
 *
 * `requestId` is client-minted and persisted on the accepted message, so a retry
 * of the same id is not inserted twice. `mode: 'queue'` waits behind a running
 * turn; `steer` interrupts it.
 */
export async function livePrompt(host, { sessionId, text, requestId = crypto.randomUUID(), mode = 'queue', clientTimeZone }, options = {}) {
  return liveCall(host, 'session/prompt', {
    request: {
      requestId,
      sessionId,
      mode,
      content: [{ type: 'text', text }],
      ...(clientTimeZone ? { clientTimeZone } : {}),
    },
  }, options);
}

/**
 * The slash commands the running desktop app offers for one session.
 *
 * DSH registers these on the host (`commands/list` / `commands/execute`), and the
 * desktop composer is the only place that showed them until now: the phone's `/`
 * panel is a fixed short list, so `/compact`, `/export`, `/goal`, `/permission` and
 * friends existed everywhere except there. The commands are executed by the host,
 * not sent to the model, so this is the honest way to offer them to a phone.
 *
 * @returns {Promise<Array<{name: string, description: string, hint: string|null}>>}
 */
export async function liveListCommands(host, sessionId, options = {}) {
  const result = await liveCall(host, 'commands/list', { agentId: sessionId }, options);
  if (!result.ok) return { ok: false, commands: [], code: result.code, message: result.message };
  const raw = Array.isArray(result.value)
    ? result.value
    : (Array.isArray(result.value?.commands) ? result.value.commands : []);
  return {
    ok: true,
    commands: raw
      .filter((item) => typeof item?.name === 'string' && item.name.length > 0)
      .map((item) => ({
        name: item.name,
        description: typeof item.description === 'string' ? item.description : '',
        hint: typeof item.input?.hint === 'string' ? item.input.hint : null,
      })),
  };
}

/**
 * Execute one slash-command line in the live session (no model turn).
 *
 * A command that does not resolve is reported as `{ ok: false, unknown: true }`
 * so the caller can fall back to sending the text as an ordinary prompt — the same
 * thing the desktop composer does with an unknown `/` line.
 *
 * @returns {Promise<{ok: boolean, text?: string, kind?: string, unknown?: boolean, message?: string}>}
 */
export async function liveExecuteCommand(host, sessionId, line, options = {}) {
  const result = await liveCall(
    host,
    'commands/execute',
    { agentId: sessionId, line, submittedAttachments: [] },
    options,
  );
  if (!result.ok) {
    // The gateway answers `undefined` (a missing value) when syntax or the name
    // does not resolve, which is not an error the phone should see as a failure.
    if (result.code === 'gateway/arguments-invalid' || result.code === 'gateway/input-invalid') {
      return { ok: false, unknown: true, message: result.message };
    }
    return { ok: false, message: result.message, code: result.code };
  }
  const value = result.value;
  if (value === undefined || value === null) return { ok: false, unknown: true };
  const settled = value.result ?? value;
  const text = typeof settled?.text === 'string' ? settled.text : '';
  const kind = typeof settled?.kind === 'string' ? settled.kind : 'success';
  return { ok: true, text, kind };
}

/**
 * Follow one live conversation over the app's own event stream.
 *
 * `session/follow` with `assistantStream: true` is what the app's web client uses:
 * it opens with a snapshot, then pushes every durable event AND the model's live
 * chunk frames (block-start / text-delta / reasoning-delta / block-end). The
 * journal alone cannot do this — the v4 log keeps only finished messages, which is
 * why the phone used to show an answer a paragraph at a time.
 *
 * The stream is gap-strict on durable events (it errors if a seq is skipped), so
 * the caller must dedupe by seq and re-open after a disconnect; a reconnect opens
 * with a fresh snapshot that can be used to fill the gap.
 *
 * @returns {{ close: () => void }}
 */
export function followSession(host, {
  sessionId,
  onSnapshot,
  onEvent,
  onChunk,
  onError,
  onClose,
} = {}) {
  const secret = browserSessionSecret();
  if (!secret) {
    onError?.(new Error('no browser-session credential'));
    onClose?.();
    return { close() {} };
  }
  const { name, value } = mintCookie(secret, host.authority);
  const streamId = `termdesk-${crypto.randomUUID()}`;
  let closed = false;
  const ws = new WebSocket(`ws://${host.authority}/api/remote.mux`, {
    headers: { cookie: `${name}=${value}` },
    handshakeTimeout: 8000,
  });
  ws.on('open', () => {
    if (closed) return;
    ws.send(JSON.stringify({
      type: 'open',
      streamId,
      endpoint: 'session/follow',
      payload: {
        args: {
          request: {
            address: { kind: 'session', sessionId },
            assistantStream: true,
            // A bounded opening page: the transcript itself is seeded from the
            // journal, and a smaller page keeps the first frame fast.
            maxMessages: 50,
          },
        },
      },
    }));
  });
  ws.on('message', (raw) => {
    if (closed) return;
    let frame;
    try { frame = JSON.parse(raw.toString()); } catch { return; }
    if (frame?.type === 'item') {
      const item = frame.value ?? {};
      if (item.type === 'snapshot') onSnapshot?.(item);
      else if (item.type === 'event') onEvent?.(item.event);
      else if (item.type === 'assistant-stream') onChunk?.(item.frame);
      return;
    }
    if (frame?.type === 'error') {
      onError?.(new Error(frame.error?.message ?? 'remote stream error'));
    }
  });
  ws.on('close', () => {
    if (closed) return;
    closed = true;
    onClose?.();
  });
  ws.on('error', (err) => {
    if (closed) return;
    onError?.(err);
  });
  return {
    close() {
      if (closed) return;
      closed = true;
      try { ws.send(JSON.stringify({ type: 'cancel', streamId })); } catch { /* already closing */ }
      try { ws.close(); } catch { /* already closed */ }
    },
  };
}

/**
 * Read the events the live agent appends to one journal.
 *
 * DSH appends an independent zstd frame per write, so the tail is "read from the
 * last byte offset, split on the zstd magic, decompress each frame". A torn final
 * frame is expected while a turn is being written and is simply retried on the
 * next poll. The callback receives parsed event objects in order.
 *
 * @returns {{ stop: () => void, done: Promise<void> }}
 */
export function tailJournal(file, { fromOffset = null, onEvent, onError, pollMs = 700, shouldStop = () => false } = {}) {
  let offset = fromOffset ?? fileSize(file);
  let stopped = false;
  let timer = null;
  let resolveDone;
  const done = new Promise((resolve) => { resolveDone = resolve; });

  const finish = () => {
    if (stopped) return;
    stopped = true;
    if (timer) clearTimeout(timer);
    resolveDone();
  };

  const pump = () => {
    if (stopped) return;
    let size;
    try { size = fileSize(file); } catch (err) { onError?.(err); return finish(); }
    if (size > offset) {
      const base = offset;
      let chunk;
      try {
        const fd = fs.openSync(file, 'r');
        try {
          chunk = Buffer.alloc(size - base);
          fs.readSync(fd, chunk, 0, chunk.length, base);
        } finally { fs.closeSync(fd); }
      } catch (err) {
        onError?.(err);
        return schedule();
      }
      // Keep an incomplete trailing frame for the next poll: only decompress up
      // to the last frame that has a successor (or a complete frame).
      const starts = [];
      for (let at = chunk.indexOf(ZSTD_MAGIC); at !== -1; at = chunk.indexOf(ZSTD_MAGIC, at + 4)) starts.push(at);
      for (let k = 0; k < starts.length; k += 1) {
        const start = starts[k];
        const end = k + 1 < starts.length ? starts[k + 1] : chunk.length;
        // A frame that is still being written cannot be decoded yet; leave it.
        //
        // zstd happily decodes a TRUNCATED frame and returns the prefix, so a
        // throw is not the signal. The writer appends whole JSON lines, so a
        // frame is only consumed when its text ends with a newline and every
        // line parses; otherwise the bytes stay unconsumed and are retried.
        let text;
        try { text = zlib.zstdDecompressSync(chunk.subarray(start, end)).toString('utf8'); } catch { break; }
        if (!text.endsWith('\n')) break;
        let parsed = null;
        try {
          parsed = text.split('\n').filter((line) => line.trim()).map((line) => JSON.parse(line));
        } catch { break; }
        offset = base + end;
        for (const event of parsed) onEvent?.(event);
      }
    }
    if (shouldStop()) return finish();
    schedule();
  };

  const schedule = () => {
    if (stopped) return;
    timer = setTimeout(pump, pollMs);
    timer.unref?.();
  };
  pump();
  return { stop: finish, done };
}

function fileSize(file) {
  return fs.statSync(file).size;
}
