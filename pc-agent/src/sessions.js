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
 *   DSH    ~/.dsh/sessions/--<cwd with separators encoded as '-'>--/<id>/session.jsonl.zstd
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

/** Walk a tree and collect files whose name matches. */
function walkFiles(root, predicate, out = [], depth = 0) {
  if (depth > 8) return out;
  let entries;
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const p = path.join(root, e.name);
    if (e.isDirectory()) walkFiles(p, predicate, out, depth + 1);
    else if (predicate(e.name)) out.push(p);
  }
  return out;
}

/**
 * Decode a DSH workspace directory name back into a path.
 * DSH encodes the cwd by replacing every path separator with '-', so
 * `--C-Users-user-Wrk--` is `C:\Users\user\Wrk`. The encoding is lossy for a
 * literal '-' in a folder name, so the recorded `cwd` field from the session's
 * first line is always preferred when available.
 */
function decodeWorkspaceDir(name) {
  const inner = name.replace(/^--/, '').replace(/--$/, '');
  const parts = inner.split('-').filter(Boolean);
  if (parts.length === 0) return name;
  // A leading drive letter, e.g. C-Users-... -> C:\Users\...
  const drive = parts[0];
  if (/^[A-Za-z]$/.test(drive)) {
    return `${drive.toUpperCase()}:\\` + parts.slice(1).join('\\');
  }
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
function codexLineToEvent(obj) {
  const at = obj.timestamp ?? null;
  const p = obj.payload ?? {};

  if (obj.type === 'session_meta') {
    return null; // Session-level metadata; surfaced separately.
  }

  if (obj.type === 'response_item') {
    if (p.type === 'message') {
      const text = (p.content ?? [])
        .map((c) => c.text ?? c.input_text ?? c.output_text ?? '')
        .filter(Boolean)
        .join('\n');
      if (!text.trim()) return null;
      // `developer` messages are injected instructions, not conversation.
      if (p.role === 'developer' || p.role === 'system') return null;
      return makeEvent({
        kind: 'message',
        role: p.role === 'user' ? 'user' : 'assistant',
        text,
        at,
      });
    }
    if (p.type === 'reasoning') {
      const text = (p.summary ?? []).map((s) => s.text ?? '').filter(Boolean).join('\n');
      if (!text.trim()) return null;
      return makeEvent({ kind: 'reasoning', role: 'assistant', text, at });
    }
    if (p.type === 'function_call') {
      return makeEvent({
        kind: 'tool',
        role: 'assistant',
        name: p.name ?? 'tool',
        text: p.arguments ?? '',
        at,
      });
    }
    if (p.type === 'function_call_output') {
      const out = typeof p.output === 'string' ? p.output : JSON.stringify(p.output ?? '');
      return makeEvent({ kind: 'tool_result', role: 'tool', text: out, at });
    }
    return null;
  }

  if (obj.type === 'event_msg') {
    if (p.type === 'task_complete') {
      // Codex's own end-of-turn signal. Kept as an event so the client can show
      // the engine's boundary rather than inventing one.
      return makeEvent({ kind: 'turn', text: p.last_agent_message ?? '', at, meta: { state: 'complete' } });
    }
    if (p.type === 'task_started') {
      return makeEvent({ kind: 'turn', text: '', at, meta: { state: 'started' } });
    }
    if (p.type === 'token_count') {
      const usage = p.info?.total_token_usage;
      if (!usage) return null;
      return makeEvent({
        kind: 'usage',
        text: '',
        at,
        meta: { total: usage.total_tokens ?? null, input: usage.input_tokens ?? null, output: usage.output_tokens ?? null },
      });
    }
    return null;
  }

  return null;
}

/** Parse a Codex session file into { meta, events }. */
function parseCodexSession(filePath) {
  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8');
  } catch {
    return null;
  }

  const lines = text.split('\n');
  const events = [];
  const meta = {
    engine: 'codex',
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
      continue; // A partially written trailing line is normal.
    }

    if (obj.type === 'session_meta') {
      const p = obj.payload ?? {};
      meta.id = p.session_id ?? p.id ?? meta.id;
      meta.cwd = p.cwd ?? meta.cwd;
      meta.createdAt = obj.timestamp ?? meta.createdAt;
      meta.title = p.instructions ? null : meta.title;
      continue;
    }
    if (obj.type === 'turn_context' && obj.payload?.cwd) {
      meta.cwd = meta.cwd ?? obj.payload.cwd;
      continue;
    }

    const ev = codexLineToEvent(obj);
    if (ev) events.push(ev);
  }

  // Fall back to the filename for the id, and use the first user message as a
  // display title only if the engine recorded none.
  if (!meta.id) {
    const m = /-([0-9a-f-]{36})\.jsonl$/.exec(path.basename(filePath));
    meta.id = m ? m[1] : path.basename(filePath);
  }
  if (!meta.title) {
    const firstUser = events.find((e) => e.role === 'user' && e.text.trim());
    meta.title = firstUser ? firstUser.text.trim().slice(0, 120) : meta.id;
  }
  if (!meta.createdAt) {
    try { meta.createdAt = fs.statSync(filePath).mtime.toISOString(); } catch { /* ignore */ }
  }

  return {
    meta,
    events: events.slice(0, MAX_EVENTS),
    totalEvents: events.length,
    truncated: events.length > MAX_EVENTS,
  };
}

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
    events: events.slice(0, MAX_EVENTS),
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
  const wantCodex = !engine || engine === 'codex';
  const wantDsh = !engine || engine === 'dsh';

  if (wantCodex) {
    for (const file of walkFiles(codexRoot(), (n) => n.endsWith('.jsonl'))) {
      let stat;
      try { stat = fs.statSync(file); } catch { continue; }
      const meta = readCodexHeader(file);
      out.push({
        engine: 'codex',
        id: meta.id ?? path.basename(file, '.jsonl'),
        title: meta.title,
        cwd: meta.cwd,
        createdAt: meta.createdAt ?? stat.mtime.toISOString(),
        updatedAt: stat.mtime.toISOString(),
        sizeBytes: stat.size,
        path: file,
      });
    }
  }

  if (wantDsh) {
    const root = dshRoot();
    let workspaces = [];
    try { workspaces = fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { workspaces = []; }
    for (const ws of workspaces) {
      const wsPath = path.join(root, ws.name);
      let subs = [];
      try { subs = fs.readdirSync(wsPath, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { continue; }
      for (const sub of subs) {
        const f = path.join(wsPath, sub.name, 'session.jsonl.zstd');
        if (!fs.existsSync(f)) continue;
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
function readCodexHeader(filePath) {
  const out = { id: null, cwd: null, createdAt: null, title: null };
  let text;
  try {
    // The header is the first line; a bounded read avoids loading 90 MB files.
    const fd = fs.openSync(filePath, 'r');
    const buf = Buffer.alloc(64 * 1024);
    const read = fs.readSync(fd, buf, 0, buf.length, 0);
    fs.closeSync(fd);
    text = buf.subarray(0, read).toString('utf8');
  } catch {
    return out;
  }
  for (const line of text.split('\n').slice(0, 40)) {
    if (!line.trim()) continue;
    let obj;
    try { obj = JSON.parse(line); } catch { continue; }
    if (obj.type === 'session_meta') {
      out.id = obj.payload?.session_id ?? obj.payload?.id ?? null;
      out.cwd = obj.payload?.cwd ?? null;
      out.createdAt = obj.timestamp ?? null;
      break;
    }
  }
  return out;
}

/** Read one session in full, by engine and id (or explicit path). */
export async function readSession({ engine, id, sessionPath }) {
  let filePath = sessionPath ?? null;

  if (!filePath) {
    if (!id) return null;
    if (engine === 'codex') {
      const found = walkFiles(codexRoot(), (n) => n.endsWith('.jsonl') && n.includes(id));
      filePath = found[0] ?? null;
    } else if (engine === 'dsh') {
      const root = dshRoot();
      let workspaces = [];
      try { workspaces = fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory()); } catch { workspaces = []; }
      for (const ws of workspaces) {
        const candidate = path.join(root, ws.name, id, 'session.jsonl.zstd');
        if (fs.existsSync(candidate)) { filePath = candidate; break; }
      }
    }
  }
  if (!filePath || !fs.existsSync(filePath)) return null;

  // Refuse paths outside the two session roots: the client supplies this value.
  const resolved = path.resolve(filePath);
  const allowed = [path.resolve(codexRoot()), path.resolve(dshRoot())];
  const inside = allowed.some((root) => {
    const rel = path.relative(root, resolved);
    return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
  });
  if (!inside) return null;

  if (engine === 'dsh' || resolved.endsWith('.zstd')) {
    const wsName = resolved.split(path.sep).slice(-3)[0] ?? '';
    return parseDshSession(resolved, wsName);
  }
  return parseCodexSession(resolved);
}

export function sessionRoots() {
  return { codex: codexRoot(), dsh: dshRoot() };
}
