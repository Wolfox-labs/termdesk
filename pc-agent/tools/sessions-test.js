/**
 * Session-reading checks against the real session stores on this machine.
 *
 * These are read-only. They assert the parser handles the layouts actually
 * present, including the multi-frame zstd format that a naive single decompress
 * silently truncates to nothing useful.
 *
 *   node tools/sessions-test.js
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  listSessions,
  readSession,
  sessionRoots,
} from '../src/sessions.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const roots = sessionRoots();
console.log(`codex root: ${roots.codex}`);
console.log(`dsh root  : ${roots.dsh}`);

try {
  // --- listing ------------------------------------------------------------
  const all = await listSessions();
  const dsh = all.filter((s) => s.engine === 'dsh');
  check('lists sessions from disk', all.length > 0, `${all.length} sessions`);
  // Codex sessions are owned by the kernel now (thread/list + thread/read) and
  // are covered by tools/codex-adapter-test.js and tools/codex-ws-e2e.js. The
  // disk reader must no longer produce them — that is the contract here.
  check('disk reader produces no codex entries', all.every((s) => s.engine !== 'codex'),
    [...new Set(all.map((s) => s.engine))].join(','));
  check('readSession refuses codex outright',
    (await readSession({ engine: 'codex', id: 'not-a-thread' })) === null);
  check('finds dsh sessions', dsh.length > 0, `${dsh.length}`);
  check('list is sorted newest-first', (() => {
    for (let i = 1; i < Math.min(all.length, 40); i += 1) {
      if (new Date(all[i].updatedAt) > new Date(all[i - 1].updatedAt)) return false;
    }
    return true;
  })());

  check('every entry has an engine and id', all.every((s) => s.engine && s.id));
  check('every entry has a path', all.every((s) => typeof s.path === 'string' && s.path.length > 0));
  check('no entry leaked outside the session roots', all.every((s) => s.path.startsWith(roots.dsh)));

  // Working directory is required for the workspace index in the app.
  const withCwd = all.filter((s) => s.cwd);
  check('most entries report a working directory', withCwd.length > all.length * 0.5,
    `${withCwd.length}/${all.length}`);
  const dshCwd = dsh.filter((s) => s.cwd).length;
  check('dsh entries report a cwd', dshCwd > 0, `${dshCwd}/${dsh.length}`);

  // The DSH workspace directory name must decode back to a Windows path.
  const sample = dsh.find((s) => s.cwd && /^[A-Z]:\\/.test(s.cwd));
  check('dsh workspace names decode to drive paths', Boolean(sample), sample?.cwd);
  // --- reading a dsh session ---------------------------------------------
  const dshPick = dsh.filter((s) => s.sizeBytes > 100000)[0] ?? dsh[0];
  const dshRead = await readSession({ engine: 'dsh', id: dshPick.id });
  check('reads a dsh session (multi-frame zstd)', Boolean(dshRead), dshPick.id);
  check('dsh session has events', (dshRead?.events.length ?? 0) > 0, `${dshRead?.events.length} events`);

  const dshKinds = [...new Set((dshRead?.events ?? []).map((e) => e.kind))];
  check('dsh events include message content', dshKinds.includes('message'), dshKinds.join(','));

  // The engine's own compaction summary must pass through untouched, since the
  // client displays engine output rather than synthesising its own.
  const bigDsh = dsh.filter((s) => s.sizeBytes > 500000).slice(0, 12);
  let sawEngineSummary = false;
  for (const s of bigDsh) {
    const r = await readSession({ engine: 'dsh', id: s.id });
    if (r?.events.some((e) => e.kind === 'engine_summary')) { sawEngineSummary = true; break; }
  }
  check('dsh engine summaries pass through verbatim', sawEngineSummary,
    sawEngineSummary ? 'compaction/summary found' : 'none in sampled sessions');

  // --- every session file on disk is listed, whatever it is called ---------
  // The invariant rather than the file name, because this test previously agreed with
  // the reader on one hard-coded name and therefore could not see that 23 DSH sessions
  // were invisible. It now walks the store, finds anything shaped like a session file,
  // and insists the index contains it — so the next rename fails loudly here instead of
  // quietly shortening the phone's list.
  const sessionFilePattern = /^session(\.[\w-]+)?\.jsonl\.zstd$/;
  const onDisk = new Map(); // session id -> file name found in its directory
  for (const ws of fs.readdirSync(roots.dsh, { withFileTypes: true })) {
    if (!ws.isDirectory()) continue;
    const wsPath = path.join(roots.dsh, ws.name);
    for (const sub of fs.readdirSync(wsPath, { withFileTypes: true })) {
      if (!sub.isDirectory()) continue;
      let names = [];
      try { names = fs.readdirSync(path.join(wsPath, sub.name)); } catch { continue; }
      const hit = names.find((n) => sessionFilePattern.test(n));
      if (hit) onDisk.set(sub.name, hit);
    }
  }
  const listedIds = new Set(dsh.map((s) => s.id));
  const unlisted = [...onDisk.entries()].filter(([id]) => !listedIds.has(id));
  check('every session file on disk appears in the index', unlisted.length === 0,
    unlisted.length === 0
      ? `${onDisk.size} session directories, all listed`
      : `${unlisted.length} missing, e.g. ${unlisted.slice(0, 3).map(([id, n]) => `${id} (${n})`).join(', ')}`);

  const knownNames = new Set(['session.jsonl.zstd', 'session.v4.jsonl.zstd']);
  const unknownNames = [...new Set([...onDisk.values()])].filter((n) => !knownNames.has(n));
  check('no session file is named something the reader ignores', unknownNames.length === 0,
    unknownNames.join(',') || 'only known names present');

  // Listing is not enough: a session that lists and then opens empty is worse than one
  // that is absent, because it looks like an answer.
  const v4Ids = [...onDisk.entries()].filter(([, n]) => n === 'session.v4.jsonl.zstd').map(([id]) => id);
  if (v4Ids.length > 0) {
    const read = [];
    for (const id of v4Ids) read.push(await readSession({ engine: 'dsh', id }));
    const found = read.filter(Boolean).length;
    const withEvents = read.filter((r) => (r?.events.length ?? 0) > 0).length;
    check('reads v4-named dsh sessions', found === v4Ids.length, `${found}/${v4Ids.length}`);
    // Some sessions are genuinely empty — created, then nothing was said — so the bar is
    // "most of them", measured: 20 of 23 carried events, the other 3 were ~0.3 KB stubs.
    check('v4 sessions carry their events', withEvents >= Math.ceil(v4Ids.length * 0.8),
      `${withEvents}/${v4Ids.length} with events`);
  } else {
    console.log('SKIP  no v4-named dsh session on this machine');
  }

  // --- truncation guard ---------------------------------------------------
  const huge = all.filter((s) => s.sizeBytes > 5 * 1024 * 1024)[0];
  if (huge) {
    const r = await readSession({ engine: huge.engine, id: huge.id });
    check('huge session is capped rather than unbounded', (r?.events.length ?? 0) <= 4000,
      `${r?.events.length} of ${r?.totalEvents} events from ${(huge.sizeBytes / 1048576).toFixed(1)} MB`);
    // 11 MB of raw lines can compress down to a few hundred displayable events,
    // so truncation must be reported from the event count, not the byte size.
    check('truncation flag matches the event count',
      r?.truncated === ((r?.totalEvents ?? 0) > 4000),
      `truncated=${r?.truncated} totalEvents=${r?.totalEvents}`);
  } else {
    console.log('SKIP  no session larger than 5 MB to test the cap');
  }

  // --- path safety --------------------------------------------------------
  const escape = await readSession({ engine: 'codex', sessionPath: 'C:\\Windows\\win.ini' });
  check('refuses a path outside the session roots', escape === null);
  const escape2 = await readSession({ engine: 'dsh', sessionPath: 'C:\\Windows\\System32\\drivers\\etc\\hosts' });
  check('refuses an outside path for dsh too', escape2 === null);

  const missing = await readSession({ engine: 'codex', id: 'no-such-session-id' });
  check('returns null for an unknown id', missing === null);
} catch (err) {
  check('session harness completed', false, err.message);
}

const failures = results.filter((r) => !r.passed).length;
console.log(`\nSessions: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
