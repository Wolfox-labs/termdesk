/**
 * Reopening a conversation whose live mirror was reaped.
 *
 * The idle reaper frees a chat 45 minutes after the phone last *sent* something. A
 * live-attached chat only watches, so an active desktop conversation used to be
 * reaped mid-turn: the mirror stopped, the transcript froze at the last event that
 * had arrived, and reopening joined the same frozen handle — the phone showed a
 * conversation that had visibly moved on in the desktop app.
 *
 * What is pinned here:
 *   - an unchanged journal is not re-read (reopening a healthy chat costs one stat);
 *   - a journal that moved is re-read, replacing the transcript and keeping exactly
 *     one copy of each line (no duplicates from the old seed);
 *   - the re-read re-attaches the live mirror when the desktop app is running, and
 *     says so in words when it is not;
 *   - mirrored events count as activity, so a watching phone is not reaped, while a
 *     truly idle chat still is;
 *   - `resume` reports the refresh instead of handing back the stale copy.
 *
 *   node tools/dsh-stale-refresh-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

import { ChatManager } from '../src/chat.js';
import { Chat } from '../src/chat/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-stale-'));
process.env.TERMDESK_DSH_DIR = tmp;

const sessionId = 'session-stale-0001';
const workspaceDir = path.join(tmp, 'sessions', '--E-aiPic-demo--', sessionId);
fs.mkdirSync(workspaceDir, { recursive: true });
const journal = path.join(workspaceDir, 'session.v4.jsonl.zstd');

const frame = (lines) => zlib.zstdCompressSync(
  Buffer.from(lines.map((line) => JSON.stringify(line)).join('\n') + '\n', 'utf8'),
);
const message = (seq, role, text) => (role === 'user'
  ? { type: 'user/message', seq, time: Date.now(), data: { content: [{ type: 'text', text }], source: { kind: 'user' } } }
  : { type: 'assistant/message', seq, time: Date.now(), data: { message: { role: 'assistant', content: [{ type: 'text', text }] } } });

fs.writeFileSync(journal, Buffer.concat([
  frame([{ type: 'session', id: sessionId, cwd: 'E:\\aiPic\\demo', time: Date.now() }]),
  frame([message(2, 'user', '旧问题'), message(3, 'assistant', '旧回答')]),
]));
const append = (lines) => fs.appendFileSync(journal, frame(lines));
const seededNow = () => fs.statSync(journal).mtime.toISOString();

const manager = new ChatManager();
const chat = new Chat({
  id: 'c-stale',
  title: 'demo',
  cwd: tmp,
  engine: 'dsh',
  provider: 'demo',
  model: 'demo',
  nativeSessionId: sessionId,
});
manager.chats.set(chat.id, chat);
chat.push({ kind: 'message', role: 'assistant', text: '旧回答' });
chat.historySeededAt = seededNow();
chat.lastUsedAt = Date.now() - 46 * 60 * 1000;
chat.status = 'idle';
chat.ready = false;

// --- unchanged journal -------------------------------------------------------
{
  const refreshed = await manager.refreshStaleDsh(chat, { discover: () => null });
  check('an unchanged journal is not decompressed again', refreshed === false, String(refreshed));
  check('and the existing transcript is left alone',
    chat.events.length === 1 && chat.events[0].text === '旧回答', `${chat.events.length} events`);
}

// --- the journal moved while the phone was not looking -----------------------
{
  append([message(4, 'user', '新问题'), message(5, 'assistant', '新回答')]);
  await new Promise((resolve) => setTimeout(resolve, 30));
  check('the journal really moved', seededNow() !== chat.historySeededAt,
    `${chat.historySeededAt} -> ${seededNow()}`);

  const refreshed = await manager.refreshStaleDsh(chat, { discover: () => null });
  check('a frozen chat is re-read once its journal moved', refreshed === true, String(refreshed));
  check('the re-read transcript ends with the new answer',
    chat.events.some((e) => e.text === '新回答'), `${chat.events.length} events`);
  check('the old line is not duplicated by the merge',
    chat.events.filter((e) => e.text === '旧问题').length === 1,
    String(chat.events.filter((e) => e.text === '旧问题').length));
  check('without a desktop host the chat is not marked live',
    chat.ready === false && chat.liveHost === null);
  check('and the transcript says it was re-read, not resumed',
    chat.events.some((e) => /重新读取/.test(e.text ?? '') && /桌面端不在运行/.test(e.text ?? '')));
}

// --- a chat that is live or busy must never be rewritten ---------------------
{
  const before = chat.events.length;
  chat.ready = true;
  check('a healthy live chat is not re-read',
    (await manager.refreshStaleDsh(chat)) === false && chat.events.length === before);
  chat.ready = false;
  chat.status = 'running';
  check('a running turn is not replaced',
    (await manager.refreshStaleDsh(chat)) === false && chat.events.length === before);
  chat.status = 'idle';
}

// --- re-attach the mirror when the desktop app is back ------------------------
{
  append([message(6, 'user', '再问'), message(7, 'assistant', '再答')]);
  await new Promise((resolve) => setTimeout(resolve, 30));
  const watched = [];
  manager.startLiveWatch = (target, id) => watched.push(id);
  const refreshed = await manager.refreshDshChat(chat, {
    discover: () => ({ pid: 1, port: 9, authority: '127.0.0.1:9' }),
  });
  check('the re-read re-attaches the live mirror', refreshed === true && watched[0] === sessionId,
    watched.join(','));
  check('the chat is ready and bound to the host again',
    chat.ready === true && chat.liveHost?.port === 9 && chat.kernelLive === true);
  check('a pending take-over is cleared once the desktop owns the session',
    chat.pendingResume === false);
  check('and the transcript says it is following again',
    chat.events.some((e) => /接上桌面端的实时会话/.test(e.text ?? '')));
}

// --- mirrored activity keeps a watching phone alive --------------------------
{
  chat.ready = true;
  chat.lastUsedAt = Date.now() - 46 * 60 * 1000;
  const before = chat.lastUsedAt;
  manager.handleLiveJournalEvent(chat, { type: 'turn/start', seq: 7100, data: {} });
  check('one mirrored event refreshes the idle clock', chat.lastUsedAt > before);
  manager.reapIdle();
  check('a chat the desktop is writing into is not reaped', chat.ready === true);

  chat.lastUsedAt = Date.now() - 46 * 60 * 1000;
  manager.reapIdle();
  check('a genuinely idle chat is still freed', chat.ready === false);
}

// --- reopening reports the refresh -------------------------------------------
{
  chat.ready = false;
  const original = manager.refreshStaleDsh;
  manager.refreshStaleDsh = async () => true;
  const joined = await manager.resume({ engine: 'dsh', id: sessionId });
  manager.refreshStaleDsh = original;
  check('reopening a reaped conversation joins and refreshes it',
    joined.ok === true && joined.joined === true && joined.refreshed === true,
    JSON.stringify({ ok: joined.ok, joined: joined.joined, refreshed: joined.refreshed }));
  check('no takeover warning is attached to a refresh',
    joined.note === null, String(joined.note));
}

manager.disposeAll();
fs.rmSync(tmp, { recursive: true, force: true });

const failed = results.filter((r) => !r.passed).length;
console.log(`\nDSH stale refresh: ${results.length - failed}/${results.length} passed`);
process.exit(failed === 0 ? 0 : 1);
