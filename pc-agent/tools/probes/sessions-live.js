/**
 * Session frames over the live protocol, plus a raw-frame type guard.
 *
 *   node tools/sessions-live.js
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WebSocket } from 'ws';

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const ws = new WebSocket(`ws://${HOST}:${PORT}`);
const frames = [];
ws.on('message', (raw) => frames.push(JSON.parse(raw.toString())));
const send = (o) => ws.send(JSON.stringify(o));
const waitFor = async (fn, ms = 60000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 120));
  }
  return null;
};
const find = (t) => frames.filter((f) => f.type === t);

await new Promise((r) => ws.on('open', () => {
  send({ type: 'auth', token });
  setTimeout(r, 800);
}));

try {
  // --- list ---------------------------------------------------------------
  send({ type: 'sessions.list' });
  const list = await waitFor(() => find('sessions')[0]);
  check('lists sessions over the wire', Boolean(list), `${list?.total} sessions`);
  check('groups sessions into workspaces', (list?.workspaces?.length ?? 0) > 0,
    `${list?.workspaces?.length} workspaces`);
  check('reports the session roots', Boolean(list?.roots?.codex && list?.roots?.dsh), list?.roots?.dsh);

  const ws0 = list.workspaces[0];
  check('a workspace reports its cwd and count', Boolean(ws0?.cwd) && ws0.count > 0,
    `${ws0?.cwd} (${ws0?.count})`);
  check('a workspace lists the engines present', Array.isArray(ws0?.engines) && ws0.engines.length > 0,
    ws0?.engines?.join('+'));

  // Workspaces are newest-first, which is what the index shows.
  check('workspaces are sorted by recency', (() => {
    for (let i = 1; i < Math.min(list.workspaces.length, 20); i += 1) {
      if (new Date(list.workspaces[i].latestAt) > new Date(list.workspaces[i - 1].latestAt)) return false;
    }
    return true;
  })());

  check('every listed session has an engine, id and cwd',
    list.sessions.every((s) => s.engine && s.id && s.cwd));

  // --- read a dsh session (the harder format) -----------------------------
  const dshSession = list.sessions.find((s) => s.engine === 'dsh' && s.sizeBytes > 200000);
  check('found a substantial dsh session to read', Boolean(dshSession),
    dshSession ? `${(dshSession.sizeBytes / 1024).toFixed(0)}KB` : 'none');

  if (dshSession) {
    const before = frames.length;
    send({ type: 'sessions.read', engine: 'dsh', sessionId: dshSession.id });
    // Scope the wait to frames received after this request: `find('session')`
    // would otherwise return a previous response and silently pass on stale data.
    const detail = await waitFor(() => frames.slice(before).find((f) => f.type === 'session'));
    check('reads a dsh session over the wire', Boolean(detail), `${detail?.events?.length} events`);
    check('dsh session carries its working directory', Boolean(detail?.meta?.cwd), detail?.meta?.cwd);
    check('dsh session matches the requested id', detail?.meta?.id === dshSession.id,
      `${detail?.meta?.id} vs ${dshSession.id}`);

    const roles = [...new Set((detail?.events ?? []).map((e) => e.role).filter(Boolean))];
    check('dsh session contains user and assistant turns',
      roles.includes('user') && roles.includes('assistant'), roles.join(','));

    // Injected harness context must never be attributed to the user: a
    // `user/message` whose source kind is not `user` is something the runtime
    // added (plugin context, skill catalog), not something the human typed.
    // (Stored sessions written by the desktop app carry no source kinds at all,
    // so this is an invariant check here; the live case is asserted in
    // chat-e2e.js, where injected context actually occurs.)
    const misattributed = (detail?.events ?? []).filter(
      (e) => e.role === 'user' && e.meta?.sourceKind && e.meta.sourceKind !== 'user',
    );
    check('injected context is never labelled as a user message',
      misattributed.length === 0, `${misattributed.length} misattributed of ${detail?.events?.length ?? 0} events`);

    const kinds = [...new Set((detail?.events ?? []).map((e) => e.kind))];
    check('dsh session keeps engine-native event kinds',
      kinds.some((k) => ['engine_summary', 'step', 'tool', 'command'].includes(k)), kinds.join(','));
  }

  // --- read a codex session ----------------------------------------------
  const codexSession = list.sessions.find((s) => s.engine === 'codex' && s.sizeBytes > 5000);
  if (codexSession) {
    const before = frames.length;
    send({ type: 'sessions.read', engine: 'codex', sessionId: codexSession.id });
    const detail = await waitFor(() => frames.slice(before).find((f) => f.type === 'session'));
    check('reads a codex session over the wire', Boolean(detail), `${detail?.events?.length} events`);
    check('codex session carries its working directory', Boolean(detail?.meta?.cwd), detail?.meta?.cwd);
    check('codex session matches the requested id', detail?.meta?.id === codexSession.id,
      `${detail?.meta?.id} vs ${codexSession.id}`);
    check('codex session reports its engine', detail?.meta?.engine === 'codex', detail?.meta?.engine);
  }

  // --- error paths --------------------------------------------------------
  send({ type: 'sessions.read', engine: 'codex', sessionId: 'definitely-not-real' });
  const notFound = await waitFor(() => find('error').find((f) => f.code === 'session_not_found'));
  check('reports an unknown session id', Boolean(notFound), notFound?.message);

  send({ type: 'sessions.read', engine: 'codex', path: 'C:\\Windows\\win.ini' });
  const refused = await waitFor(() => find('error').find((f) => f.code === 'session_not_found'));
  check('refuses a path outside the session roots', Boolean(refused));

  // --- no undocumented frame types ---------------------------------------
  const validTypes = new Set([
    'auth.ok', 'hello', 'status', 'procs', 'services', 'fs.listing', 'fs.file',
    'fs.written', 'fs.roots', 'term.opened', 'term.output', 'term.exit', 'term.list',
    'ai.engines', 'ai.tasks', 'ai.task', 'ai.started', 'ai.event', 'ai.finished',
    'codex.config', 'sessions', 'session', 'action.result', 'error', 'pong',
  ]);
  const unexpected = [...new Set(frames.map((f) => f.type))].filter((t) => !validTypes.has(t));
  check('every frame uses a declared protocol type', unexpected.length === 0,
    unexpected.length ? `unexpected: ${unexpected.join(', ')}` : `${frames.length} frames`);
} catch (err) {
  check('session live harness completed', false, err.message);
}

ws.close();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nSessions live: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
