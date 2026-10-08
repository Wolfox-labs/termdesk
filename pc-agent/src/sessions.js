/**
 * Reading existing Codex and DSH sessions from disk.
 *
 * Both engines already store everything a client needs, so this module only
 * *reads and normalises* — it never summarises, re-ranks or rewrites content.
 * The phone is expected to render the conversation as-is and let the user ask
 * for anything further with a slash command inside the session.
 *
 * Storage layouts were verified against real data on this machine:
 *
 *   Codex  ~/.codex/sessions/<yyyy>/<MM>/<dd>/rollout-<ts>-<uuid>.jsonl
 *          Plain UTF-8 JSONL. Each line is { type, payload, timestamp }.
 *          Types seen: session_meta, event_msg (task_started/task_complete/
 *          item_completed/token_count/thread_settings_applied), response_item
 *          (message/reasoning/function_call/function_call_output),
 *          turn_context, token_usage_record, world_state.
 *          The working directory lives in session_meta.payload.cwd.
 *
 *   DSH    ~/.dsh/sessions/--<cwd with separators encoded as '-'>--/<id>/session[.v4].jsonl.zstd
 *          Appended multi-frame zstd: every append writes a fresh independent
 *          frame, so a single decompress call stops after the first frame and
 *          yields almost nothing. Frames must be split on the zstd magic
 *          (28 B5 2F FD) and decompressed individually. A 13 MB session
 *          produced 25071 frames and ~40k events.
 *          Event types include user/message, assistant/message, assistant/chunk,
 *          reasoning-chunks, tool/call, tool/result, step/start, step/end,
 *          turn/start, turn/end and compaction/summary.
 *
 * All disk access is read-only.
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/** Cap on events returned for one session, so a huge session cannot flood a phone. */
const MAX_EVENTS = 4000;
/** Cap on sessions listed per workspace. */
const MAX_SESSIONS_PER_WORKSPACE = 300;

function codexRoot() {
  return process.env.TERMDESK_CODEX_DIR
    ? path.join(process.env.TERMDESK_CODEX_DIR, 'sessions')
    : path.join(os.homedir(), '.codex', 'sessions');
}

function dshRoot() {
  return process.env.TERMDESK_DSH_DIR
    ? path.join(process.env.TERMDESK_DSH_DIR, 'sessions')
    : path.join(os.homedir(), '.dsh', 'sessions');
}

/**
 * The file names a DSH session has used, newest format first.
 *
 * Measured on this machine (2026-10-08): of 268 session directories, 230 held only the
 * legacy name, 23 held only `session.v4.jsonl.zstd`, and 15 held both — and in all 15 the
 * v4 file was the newer one (by hours to days) and usually the smaller, because the v4
 * layout records finished events instead of every streamed delta.
 *
 * Hard-coding the legacy name therefore hid the newest sessions from the phone, and hid
 * them SILENTLY: the list was merely shorter, with nothing to say a session was missing.
 * The desktop app was writing v4 while this was being measured, so the ones it hid were
 * exactly the ones in use.
 *
 * One parser reads either file: both formats use the same event names (`user/message`
 * with `source.kind`, `assistant/message`, `tool/call`, `tool/result`, `step/*`,
 * `compaction/*`); only the streamed deltas differ, and those were never rendered.
 */
const DSH_SESSION_FILES = ['session.v4.jsonl.zstd', 'session.jsonl.zstd'];

/** The session file inside one session directory, or null when it holds none. */
function dshSessionFile(dir) {
  for (const name of DSH_SESSION_FILES) {
    const file = path.join(dir, name);
    if (fs.existsSync(file)) return file;
  }
  return null;
}

/** Walk a tree and collect files whose name matches. */
/**
 * Decode a DSH workspace directory name back into a path.
 * DSH encodes the cwd by replacing every path separator with '-', so
 * `--C-Users-alice-Wrk--` is `C:\Users\alice\Wrk`. The encoding is lossy for a
 * literal '-' in a folder name, so the recorded `cwd` field from the session's
 * first line is always preferred when available.
 */
/**
 * DSH escapes characters it cannot put in a folder name as ~XXXX hex, e.g.
 * `Hearts~0020of~0020Iron~0020IV` is "Hearts of Iron IV". Decoding it matters
 * because that string is what the phone shows as the workspace name.
 */
function decodeTildeEscapes(value) {
  return value.replace(/~([0-9A-Fa-f]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
}

function decodeWorkspaceDir(name) {
  const inner = decodeTildeEscapes(name.replace(/^--/, '').replace(/--$/, ''));
  const parts = inner.split('-').filter(Boolean);
  if (parts.length === 0) return '';
  // A leading drive letter, e.g. C-Users-... -> C:\Users\...
  const drive = parts[0];
  if (/^[A-Za-z]$/.test(drive)) {
    return `${drive.toUpperCase()}:\\` + parts.slice(1).join('\\');
  }
  // DSH also names workspace folders after a session title ("dsh", a game name
  // with ~0020 for spaces). Those are not paths, and reporting them as a working
  // directory offered the phone directories that do not exist. Unknown is the
  // honest answer; the client shows no directory instead of a wrong one. Empty
  // string (not null) so org.json's optString cannot turn it into "null".
  return '';
  return parts.join('\\');
}

/** Decompress an appended multi-frame zstd file. */
function decompressFrames(filePath) {
  const buf = fs.readFileSync(filePath);
  if (buf.length < 4) return '';

  // Fast path: a single-frame file (small sessions) decompresses directly.
  const offsets = [];
  let i = 0;
  while (true) {
    const idx = buf.indexOf(ZSTD_MAGIC, i);
    if (idx === -1) break;
    offsets.push(idx);
    i = idx + 4;
  }
  if (offsets.length === 0) return '';
  if (offsets.length === 1) {
    try {
      return zlib.zstdDecompressSync(buf).toString('utf8');
    } catch {
      return '';
    }
  }

  const parts = [];
  for (let k = 0; k < offsets.length; k += 1) {
    const start = offsets[k];
    const end = k + 1 < offsets.length ? offsets[k + 1] : buf.length;
    try {
      parts.push(zlib.zstdDecompressSync(buf.subarray(start, end)).toString('utf8'));
    } catch {
      // A torn final frame is expected while the session is being written.
    }
  }
  return parts.join('');
}

// ---------------------------------------------------------------------------
// Normalised shapes
//
// The phone should not have to know two schemas, so both engines are mapped to
// one event shape. `kind` is a display category; `role` distinguishes speakers.
// Nothing is summarised or dropped beyond truncating very large payloads.
// ---------------------------------------------------------------------------

function makeEvent({ kind, role = null, text = '', at = null, name = null, meta = null }) {
  return { kind, role, text, at, name, meta };
}

/**
 * Normalise one Codex JSONL line.
 * Returns null for lines that carry no user-visible conversation content.
 */
/** Parse a Codex session file into { meta, events }. */
/**
 * Normalise one DSH event line.
 *
 * Real payload shape (verified against a 13 MB session):
 *   { type, seq, time: <epoch ms>, data: {...}, surfaceOp }
 * Message text lives in `data.content[]` for user messages and in
 * `data.message.content[]` for assistant messages — NOT in a flat `text` field.
 * An assistant message's content array also carries its `reasoning` blocks
 * inline, so the two must be separated here.
 *
 * DSH also records streaming chunks (`assistant/chunk`, `reasoning-chunks`).
 * Only the finished forms are kept: rendering deltas would duplicate every
 * message, and assembling them is the client's business, not the reader's.
 */
function dshLineToEvent(obj) {
  const at = obj.time ? new Date(obj.time).toISOString() : null;
  const d = obj.data ?? {};
  const t = obj.type;

  if (t === 'user/message') {
    const text = contentToText(d.content);
    if (!text.trim()) return null;
    // The runtime labels who authored each user-role message. Only `user` is
    // the human's own words; `plugin`, `skill-catalog` and friends are context
    // the harness injected. Rendering those as the user's messages would put
    // words in the user's mouth, so they are carried as engine context instead
    // of being dropped or misattributed.
    const sourceKind = d.source?.kind ?? null;
    const injected = sourceKind !== null && sourceKind !== 'user';
    return makeEvent({
      kind: injected ? 'context' : 'message',
      role: injected ? 'engine' : 'user',
      text,
      at,
      meta: { sourceKind },
    });
  }

  if (t === 'assistant/message') {
    // `data.message.content` mixes reasoning and text blocks; split them so the
    // chat view shows the answer and the reasoning stays available separately.
    const content = d.message?.content ?? d.content ?? [];
    const answer = contentToText(content.filter((c) => c?.type !== 'reasoning'));
    const reasoning = contentToText(content.filter((c) => c?.type === 'reasoning'));
    if (answer.trim()) {
      return makeEvent({ kind: 'message', role: 'assistant', text: answer, at });
    }
    if (reasoning.trim()) {
      return makeEvent({ kind: 'reasoning', role: 'assistant', text: reasoning, at });
    }
    return null;
  }

  if (t === 'reasoning') {
    const text = contentToText(d.content) || (typeof d.text === 'string' ? d.text : '');
    if (!text.trim()) return null;
    return makeEvent({ kind: 'reasoning', role: 'assistant', text, at });
  }

  if (t === 'tool/call') {
    const name = d.name ?? d.toolName ?? d.tool ?? 'tool';
    const args = d.args ?? d.arguments ?? d.input ?? '';
    return makeEvent({
      kind: 'tool',
      role: 'assistant',
      name: String(name),
      text: typeof args === 'string' ? args : JSON.stringify(args),
      at,
    });
  }

  if (t === 'tool/result') {
    const r = d.result ?? d.output ?? d.content ?? '';
    return makeEvent({
      kind: 'tool_result',
      role: 'tool',
      name: d.name ?? d.tool ?? null,
      text: typeof r === 'string' ? r : contentToText(r) || JSON.stringify(r),
      at,
    });
  }

  if (t === 'step/start') {
    return makeEvent({
      kind: 'step',
      text: '',
      at,
      meta: { state: 'started', index: d.step ?? d.index ?? null, turn: d.turn ?? null },
    });
  }
  if (t === 'step/end') {
    return makeEvent({
      kind: 'step',
      text: '',
      at,
      meta: { state: 'ended', index: d.step ?? d.index ?? null, turn: d.turn ?? null },
    });
  }
  if (t === 'turn/start') {
    return makeEvent({ kind: 'turn', text: '', at, meta: { state: 'started' } });
  }
  if (t === 'turn/end') {
    return makeEvent({ kind: 'turn', text: '', at, meta: { state: 'ended' } });
  }

  if (t === 'compaction/summary') {
    // The engine's own compaction summary. Passed through verbatim: the client
    // shows what the engine produced and never synthesises a summary of its own.
    const text = contentToText(d.content ?? d.summary) || (typeof d.text === 'string' ? d.text : '');
    if (!text.trim()) return null;
    return makeEvent({ kind: 'engine_summary', role: 'engine', text, at });
  }

  if (t === 'command/run') {
    return makeEvent({ kind: 'command', text: d.command ?? '', at });
  }
  if (t === 'command/done') {
    return makeEvent({
      kind: 'command_result',
      text: '',
      at,
      meta: { exitCode: d.exitCode ?? d.code ?? null },
    });
  }

  // todo/write is the agent's own plan for the turn; keep it as engine output.
  if (t === 'todo/write') {
    const items = d.todos ?? d.items ?? [];
    const text = Array.isArray(items)
      ? items.map((x) => `- ${x?.text ?? x?.content ?? JSON.stringify(x)}`).join('\n')
      : '';
    if (!text.trim()) return null;
    return makeEvent({ kind: 'engine_plan', role: 'engine', text, at });
  }

  return null;
}

/**
 * Normalise one live DSH event for the chat view.
 *
 * The same `{type, seq, time, data, surfaceOp}` vocabulary arrives both from a
 * stored session file and from a live SDK runtime, so the live chat reuses this
 * normaliser instead of maintaining a second, drifting copy.
 *
 * @returns {{kind, role, text, at, name, meta}|null} null when the event carries
 *   no user-visible conversation content.
 */
export function dshEventToChatEvent(obj) {
  return dshLineToEvent(obj);
}

/**
 * Flatten a content array to text.
 * Handles strings, `{type:'text', text}` and `{type:'reasoning', text}` blocks.
 */
function contentToText(content) {
  if (content == null) return '';
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) {
    if (typeof content.text === 'string') return content.text;
    return '';
  }
  return content
    .map((c) => {
      if (typeof c === 'string') return c;
      if (typeof c?.text === 'string') return c.text;
      if (typeof c?.content === 'string') return c.content;
      return '';
    })
    .filter(Boolean)
    .join('\n');
}

/** Parse a DSH session file into { meta, events }. */
function parseDshSession(filePath, workspaceName) {
  let text;
  try {
    text = decompressFrames(filePath);
  } catch {
    return null;
  }
  if (!text) return null;

  const lines = text.split('\n');
  const events = [];
  const meta = {
    engine: 'dsh',
    id: null,
    cwd: null,
    createdAt: null,
    title: null,
    filePath,
  };

  for (const line of lines) {
    if (!line.trim()) continue;
    let obj;
    try {
      obj = JSON.parse(line);
    } catch {
      continue;
    }

    if (obj.type === 'session') {
      meta.id = obj.id ?? meta.id;
      meta.cwd = obj.cwd ?? meta.cwd;
      if (obj.createdAt) meta.createdAt = new Date(obj.createdAt).toISOString();
      continue;
    }

    const ev = dshLineToEvent(obj);
    if (ev) events.push(ev);
  }

  if (!meta.id) meta.id = path.basename(path.dirname(filePath));
  if (!meta.cwd) meta.cwd = decodeWorkspaceDir(workspaceName);
  if (!meta.title) {
    const firstUser = events.find((e) => e.role === 'user' && e.text.trim());
    meta.title = firstUser ? firstUser.text.trim().slice(0, 120) : meta.id;
  }
  if (!meta.createdAt) {
    try { meta.createdAt = fs.statSync(filePath).mtime.toISOString(); } catch { /* ignore */ }
  }

  return {
    meta,
    events: events.slice(-MAX_EVENTS),
    // Report whether events were dropped. Counting the recorded events (not the
    // raw lines) is the honest signal: a 11 MB session can hold 40k raw lines
    // but only a few hundred displayable ones, and nothing is lost in that case.
    totalEvents: events.length,
    truncated: events.length > MAX_EVENTS,
  };
}

/**
 * List sessions for both engines.
 *
 * Metadata only: this reads each file's header rather than its full content so
 * a listing across 300+ sessions stays fast. Full content is loaded by
 * readSession on demand.
 */
export async function listSessions({ engine } = {}) {
  const out = [];
  const wantDsh = !engine || engine === 'dsh';

  if (wantDsh) {
    const root = dshRoot();
    let workspaces = [];
    try { workspaces = fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { workspaces = []; }
    for (const ws of workspaces) {
      const wsPath = path.join(root, ws.name);
      let subs = [];
      try { subs = fs.readdirSync(wsPath, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { continue; }
      for (const sub of subs) {
        const f = dshSessionFile(path.join(wsPath, sub.name));
        if (!f) continue;
        let stat;
        try { stat = fs.statSync(f); } catch { continue; }
        out.push({
          engine: 'dsh',
          id: sub.name,
          title: null, // Filled in on demand; the header carries no title.
          cwd: decodeWorkspaceDir(ws.name),
          createdAt: stat.birthtime.toISOString(),
          updatedAt: stat.mtime.toISOString(),
          sizeBytes: stat.size,
          path: f,
          workspaceDir: ws.name,
        });
      }
    }
  }

  out.sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));
  return out;
}

/** Read just enough of a Codex file to get its identity. */
/**
 * Read one DSH session in full.
 *
 * Codex sessions are NOT read here any more: the kernel serves them through
 * `thread/read` (see kernels/codex.js). A second reader would only drift from
 * what the kernel reports.
 */
export async function readSession({ engine, id, sessionPath }) {
  if (engine && engine !== 'dsh') return null;
  let filePath = sessionPath ?? null;

  if (!filePath) {
    if (!id) return null;
    const root = dshRoot();
    let workspaces = [];
    try { workspaces = fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { workspaces = []; }
    for (const ws of workspaces) {
      const candidate = dshSessionFile(path.join(root, ws.name, id));
      if (candidate) { filePath = candidate; break; }
    }
  }
  if (!filePath || !fs.existsSync(filePath)) return null;

  // Refuse paths outside the DSH session root: the client supplies this value.
  let resolved;
  try { resolved = fs.realpathSync(filePath); } catch { return null; }
  let allowed;
  try { allowed = fs.realpathSync(dshRoot()); } catch { allowed = path.resolve(dshRoot()); }
  const rel = path.relative(allowed, resolved);
  if (rel !== '' && (rel.startsWith('..') || path.isAbsolute(rel))) return null;

  if (resolved.endsWith('.zstd')) {
    const wsName = resolved.split(path.sep).slice(-3)[0] ?? '';
    return parseDshSession(resolved, wsName);
  }
  return null;
}
export function sessionRoots() {
  return { codex: codexRoot(), dsh: dshRoot() };
}
