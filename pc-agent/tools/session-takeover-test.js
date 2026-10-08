/**
 * Taking over a session that somebody else may be in.
 *
 * The product decision this file pins: one kernel gets input from one terminal device,
 * and the PC is where a message waits its turn — so the phone is a display shell and the
 * job here is a SEAMLESS handover, not a lock. Two things make that true:
 *
 *   - resuming a session somebody already has open returns THAT chat, because one
 *     session must not become two runtimes that then disagree about the conversation;
 *   - the phone is told what it is joining, and told when the file behind the session was
 *     written seconds ago, which is how the desktop application shows up (we can neither
 *     ask it nor lock it out — it is a third-party app).
 *
 * Everything here is hermetic: the session stores are temporary directories, so no test
 * reads or writes the real ones. That is also why the store roots are read from the
 * environment on every call rather than captured at import.
 *
 *   node tools/session-takeover-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-takeover-'));
process.env.TERMDESK_DSH_DIR = path.join(tmp, 'dsh');
process.env.TERMDESK_CODEX_DIR = path.join(tmp, 'codex');

// One DSH session in the legacy name and one in the v4 name, so both are covered.
const wsDir = path.join(process.env.TERMDESK_DSH_DIR, 'sessions', '--C-Users-someone-Wrk--');
fs.mkdirSync(path.join(wsDir, 'session-legacy'), { recursive: true });
fs.writeFileSync(path.join(wsDir, 'session-legacy', 'session.jsonl.zstd'), 'x');
fs.mkdirSync(path.join(wsDir, 'session-v4'), { recursive: true });
fs.writeFileSync(path.join(wsDir, 'session-v4', 'session.v4.jsonl.zstd'), 'x');

const uuid = '01a11182-7aec-7910-a37e-da9708b76db0';
const rolloutDir = path.join(process.env.TERMDESK_CODEX_DIR, 'sessions', '2026', '10', '08');
fs.mkdirSync(rolloutDir, { recursive: true });
fs.writeFileSync(path.join(rolloutDir, `rollout-2026-10-08T21-58-46-${uuid}.jsonl`), '{}\n');

const { lastWriteOf } = await import('../src/sessions.js');
const { ChatManager, Chat } = await import('../src/chat.js');

// --- what counts as evidence ------------------------------------------------
const legacy = lastWriteOf({ engine: 'dsh', id: 'session-legacy' });
check('a session written a moment ago reports a moment ago',
  legacy !== null && legacy.agoSeconds <= 5, JSON.stringify(legacy?.agoSeconds));
check('the write time is an ISO timestamp',
  typeof legacy?.at === 'string' && !Number.isNaN(Date.parse(legacy.at)), legacy?.at);
check('the v4-named session is found as well',
  lastWriteOf({ engine: 'dsh', id: 'session-v4' }) !== null);
check('a client-supplied path inside the store is accepted',
  lastWriteOf({ engine: 'dsh', sessionPath: path.join(wsDir, 'session-legacy', 'session.jsonl.zstd') }) !== null);
check('a client-supplied path OUTSIDE the store is refused',
  lastWriteOf({ engine: 'dsh', sessionPath: 'C:\\Windows\\win.ini' }) === null);
check('an unknown session id has no evidence',
  lastWriteOf({ engine: 'dsh', id: 'no-such-session' }) === null);
check('a codex thread is found from its rollout file',
  lastWriteOf({ engine: 'codex', id: uuid }) !== null, uuid);
check('an unknown codex thread has no evidence',
  lastWriteOf({ engine: 'codex', id: '00000000-0000-0000-0000-000000000000' }) === null);
check('a codex id that is not an id is not searched for',
  lastWriteOf({ engine: 'codex', id: '../../etc/passwd' }) === null);
// The ACP family keeps its sessions behind the kernel's own API. No evidence is not
// safety, and the next check is the one that keeps a future reader from "fixing" this
// into a reassuring answer.
check('an ACP kernel reports no evidence rather than a safe-looking answer',
  lastWriteOf({ engine: 'opencode', id: 'ses_abc' }) === null);

// --- the handover itself ----------------------------------------------------
const manager = new ChatManager();
// Real Chat instances, not stubs: `detail()` is the shape the phone renders, and a
// stubbed object made the manager's own cleanup throw rather than this test fail.
const heldChat = (id, engine, sessionId) => {
  const chat = new Chat({ id, title: id, cwd: tmp, engine, provider: null, model: null });
  chat.sessionId = sessionId;
  return chat;
};
manager.chats.set('c1', heldChat('c1', 'codex', 'thread-open'));
manager.chats.set('c2', heldChat('c2', 'dsh', 'session-legacy'));

const joinedCodex = await manager.resume({ engine: 'codex', id: 'thread-open' });
check('resuming a session somebody holds joins that same chat, not a second runtime',
  joinedCodex.ok === true && joinedCodex.chat?.id === 'c1', JSON.stringify(joinedCodex.chat));
check('and the answer says it was a join rather than a fresh open', joinedCodex.joined === true);
check('the join note names the shared runtime',
  /同一/.test(joinedCodex.note ?? ''), joinedCodex.note);

const joinedDsh = await manager.resume({ engine: 'dsh', id: 'session-legacy' });
check('a join also reports a file that was written a moment ago',
  /文件 \d+ 秒前被写过/.test(joinedDsh.note ?? ''), joinedDsh.note);
check('the sentence says what was measured rather than who wrote it',
  /可能是桌面端应用/.test(joinedDsh.note ?? ''), joinedDsh.note);

// A kernel with no verified resume entry point must be refused, and refused WITHOUT a
// note: a note under a refusal reads as "it happened anyway".
const refused = await manager.resume({ engine: 'dsh', id: 'session-nobody-has' });
check('a kernel that cannot resume is refused rather than queued',
  refused.ok === false && refused.code === 'resume_unsupported', JSON.stringify(refused));
check('a refusal carries no handover note', refused.note === undefined);

// Silence when there is nothing to say: no join, no recent write, no note.
check('no evidence and no join means no note at all',
  manager.takeoverNote({ engine: 'opencode', id: 'ses_abc', joined: false }) === null);
check('a recent write with no join still warns',
  /秒前被写过/.test(manager.takeoverNote({ engine: 'dsh', id: 'session-legacy', joined: false }) ?? ''));

// --- the measurement must happen BEFORE the kernel touches the file ----------
// Found on a real device, not by reasoning: resuming a Codex thread rewrites its rollout
// file, so a measurement taken afterwards reported OUR write as somebody else's — the
// phone said "0 seconds ago" for a session last touched two days earlier. The stub below
// performs exactly that write, and the first check fails if the ordering ever slips back.
const rollout = path.join(rolloutDir, `rollout-2026-10-08T21-58-46-${uuid}.jsonl`);
const tenMinutesAgo = new Date(Date.now() - 600_000);
fs.utimesSync(rollout, tenMinutesAgo, tenMinutesAgo);
manager.resumeCodex = async () => {
  const now = new Date();
  fs.utimesSync(rollout, now, now); // what thread/resume does to the file
  return { ok: true, chat: { id: 'c3', engine: 'codex', sessionId: uuid } };
};

const touched = await manager.resume({ engine: 'codex', id: uuid });
check('a write caused by the takeover itself is not reported as another writer',
  touched.ok === true && !/秒前被写过/.test(touched.note ?? ''), touched.note ?? '(no note)');

const now = new Date();
fs.utimesSync(rollout, now, now);
const warned = await manager.resume({ engine: 'codex', id: uuid });
check('a write that was already there IS reported',
  /秒前被写过/.test(warned.note ?? ''), warned.note ?? '(no note)');

manager.disposeAll();
try { fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* best effort */ }

const failed = results.filter((r) => !r.passed);
console.log(`\nSession takeover: ${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length === 0 ? 0 : 1);
