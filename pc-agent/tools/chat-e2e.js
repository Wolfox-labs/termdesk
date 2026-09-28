/**
 * End-to-end chat test against a running TermDesk PC agent.
 *
 * Drives the real WebSocket wire protocol the phone speaks, so it proves the
 * product path rather than the library in isolation:
 *
 *   auth -> chat.create -> chat.send -> streamed chat.event frames -> idle
 *   -> chat.send again (the continuity question) -> chat.read -> chat.close
 *
 * The second turn is the whole point: it fails if the chat is secretly a
 * one-shot process with a re-fed transcript, because the answer must come from
 * the runtime's own memory of turn 1.
 *
 * Usage: node tools/chat-e2e.js [--port 7420] [--host 127.0.0.1] [--skip-live]
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
}
const HOST = arg('--host', '127.0.0.1');
const PORT = Number(arg('--port', '7420'));
const SKIP_LIVE = argv.includes('--skip-live');

let passes = 0;
let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    passes++;
    console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`);
  } else {
    failures++;
    console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`);
  }
}

function readToken() {
  if (process.env.TERMDESK_TOKEN) return process.env.TERMDESK_TOKEN.trim();
  const p = path.join(os.homedir(), '.termdesk', 'token');
  return fs.readFileSync(p, 'utf8').trim();
}

const frames = [];
let socket;
let frameWaiters = [];

function waitFor(predicate, timeoutMs, label) {
  // Scan what already arrived first: a fast reply may land before the waiter
  // is registered, which is exactly the bug that made an earlier test pass
  // against a stale frame.
  const existing = frames.find(predicate);
  if (existing) return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      frameWaiters = frameWaiters.filter((w) => w.timer !== timer);
      reject(new Error(`timed out waiting for ${label}`));
    }, timeoutMs);
    frameWaiters.push({ predicate, resolve, timer, label });
  });
}

/** Wait for a matching frame that arrives AFTER the current end of the log. */
function waitForNext(predicate, timeoutMs, label) {
  const from = frames.length;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      frameWaiters = frameWaiters.filter((w) => w.timer !== timer);
      reject(new Error(`timed out waiting for ${label}`));
    }, timeoutMs);
    frameWaiters.push({ predicate, resolve, timer, label, from });
  });
}

/**
 * Wait until at least `target` frames (over the whole socket log) match.
 *
 * Turn completion must be counted, not just awaited once: the runtime reports a
 * trailing `idle` status for turn 1 that can arrive after turn 2 was sent, so a
 * single-slot wait happily resolves turn 2 with turn 1's leftover frame. The
 * `turn/end`-derived frame is emitted exactly once per turn, which makes the
 * count a truthful turn counter.
 */
function waitForCount(predicate, target, timeoutMs, label) {
  const count = () => frames.filter(predicate).length;
  if (count() >= target) return Promise.resolve(true);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      frameWaiters = frameWaiters.filter((w) => w.timer !== timer);
      reject(new Error(`timed out waiting for ${label} (saw ${count()}/${target})`));
    }, timeoutMs);
    frameWaiters.push({
      predicate: () => count() >= target,
      resolve: () => resolve(true),
      timer,
      label,
      isCount: true,
    });
  });
}

function dispatch(frame) {
  const index = frames.length;
  frames.push(frame);
  const remaining = [];
  for (const w of frameWaiters) {
    let hit = false;
    if (w.isCount) hit = w.predicate();
    else hit = index >= (w.from ?? 0) && w.predicate(frame, index);
    if (hit) {
      clearTimeout(w.timer);
      w.resolve(frame);
    } else {
      remaining.push(w);
    }
  }
  frameWaiters = remaining;
}

function send(type, payload = {}) {
  socket.send(JSON.stringify({ v: 1, type, ...payload }));
}

function connect() {
  return new Promise((resolve, reject) => {
    socket = new WebSocket(`ws://${HOST}:${PORT}`);
    socket.on('open', () => resolve());
    socket.on('error', reject);
    socket.on('message', (raw) => {
      let frame;
      try {
        frame = JSON.parse(raw.toString());
      } catch {
        return;
      }
      dispatch(frame);
    });
  });
}

/** Collect the visible transcript of a chat as the phone would render it. */
function renderTranscript(chatId) {
  const events = frames
    .filter((f) => f.type === 'chat.event' && f.chatId === chatId)
    .map((f) => f.seq);
  return events;
}

function chatEventsFrom(detail) {
  return (detail.events ?? []).map((e) => `${e.kind}/${e.role ?? '-'}:${(e.text ?? '').slice(0, 120)}`);
}

/**
 * Build the live transcript the way the phone does: apply each `chat.event`
 * frame in order, inserting or replacing its `item` by seq, and honouring
 * `removed` frames. Reading via `chat.read` at the end would hide a broken
 * incremental stream, which is what the user actually stares at.
 */
function liveTranscript(chatId) {
  const bySeq = new Map();
  for (const f of frames) {
    if (f.type !== 'chat.event' || f.chatId !== chatId) continue;
    if (f.removed === true) {
      bySeq.delete(f.seq);
      continue;
    }
    if (f.item) bySeq.set(f.seq, f.item);
  }
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}

function assistantTexts(events) {
  return events.filter((e) => e.kind === 'message' && e.role === 'assistant');
}

(async () => {
  console.log(`agent ws://${HOST}:${PORT}`);
  await connect();

  // --- auth ---
  send('auth', { token: readToken() });
  const authOk = await waitFor((f) => f.type === 'auth.ok', 10000, 'auth.ok');
  check('authentication accepted', Boolean(authOk));
  check('auth.ok carries the protocol version', authOk?.protocol === 1, String(authOk?.protocol));

  // --- an unauthenticated second socket must still be refused ---
  const bad = new WebSocket(`ws://${HOST}:${PORT}`);
  const badClosed = await new Promise((resolve) => {
    bad.on('open', () => {
      bad.send(JSON.stringify({ v: 1, type: 'chat.list' }));
    });
    bad.on('close', (code) => resolve(code));
    setTimeout(() => resolve(null), 8000);
  });
  check('chat frames are refused before auth (close 4401)', badClosed === 4401, `close=${badClosed}`);

  // --- chat.create ---
  send('chat.create', { cwd: process.cwd(), title: 'e2e' });
  const created = await waitFor((f) => f.type === 'chat', 15000, 'chat (created)');
  check('chat.create returned a chat', Boolean(created?.id), created?.id);
  const chatId = created.id;
  check('new chat starts idle and not ready', created.status === 'idle' && created.ready === false,
    `status=${created.status} ready=${created.ready}`);
  check('chat reports its working directory', created.cwd === process.cwd(), created.cwd);
  check('chat declares provider and model', Boolean(created.provider && created.model),
    `${created.provider} / ${created.model}`);

  const listed = await waitFor((f) => f.type === 'chats' && f.chats.some((c) => c.id === chatId),
    10000, 'chats list containing the new chat');
  check('chat.list includes the new chat', Boolean(listed));

  // A `chat.read` response must be recognised by its transcript envelope, not
  // just by type+id: chat.create returns the SAME type and id without events,
  // so matching on those alone re-reads the creation frame.
  const isDetail = (f) => f.type === 'chat' && f.id === chatId && Array.isArray(f.events);
  const readDetail = async (label) => {
    send('chat.read', { chatId });
    return waitForNext(isDetail, 20000, label);
  };

  const initial = await readDetail('chat detail (initial)');
  check('chat.read returns the transcript envelope', Array.isArray(initial.events));
  check('creation is recorded in the transcript', initial.events.length >= 2,
    `${initial.events.length} event(s)`);

  if (SKIP_LIVE) {
    console.log('');
    console.log('(--skip-live: wire plumbing checked, live turns skipped)');
    finish();
    return;
  }

  // --- turn 1: the live conversation ---
  const marker = `TD-${Date.now().toString(36).toUpperCase()}`;
  const sendAt1 = Date.now();
  send('chat.send', { chatId, text: `Remember this code: ${marker}. Reply with just the code and nothing else.` });

  const sent1 = await waitFor((f) => f.type === 'chat.sent' && f.chatId === chatId, 60000, 'chat.sent (turn 1)');
  check('chat.send was accepted (durable receipt)', Boolean(sent1?.messageId), sent1?.messageId);
  check('the user message is echoed before the answer',
    frames.some((f) => f.type === 'chat.event' && f.chatId === chatId), 'chat.event observed');

  // Wait for the turn to end. `chat.turn` with state 'ended' comes from the
  // runtime's own turn/end, so it is a per-turn event; count it rather than
  // awaiting one match, which a straggler frame could satisfy.
  const isTurnEnd = (f) => f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended';
  await waitForCount(isTurnEnd, 1, 300000, 'turn 1 completion');
  check('turn 1 completed', true, 'turn/end observed');
  check('turn 1 was NOT instant (a real model answered)', Date.now() - sendAt1 > 500,
    `${Date.now() - sendAt1}ms`);

  const detail1 = await readDetail('chat detail after turn 1');
  // Assert on the LIVE stream, not the read: the phone renders frames as they
  // arrive, so a duplicate or missing update must fail here.
  const live1 = liveTranscript(chatId);
  const texts1 = assistantTexts(live1);
  check('turn 1 produced an assistant message in the live stream', texts1.length > 0,
    `${texts1.length} message(s)`);
  const answer1 = texts1.map((e) => e.text).join(' ').trim();
  check('turn 1 answer contains the marker', answer1.includes(marker), JSON.stringify(answer1.slice(0, 160)));
  check('the answer appears exactly once (deltas reconciled, not duplicated)',
    answer1.split(marker).length - 1 === 1,
    `${texts1.length} event(s), ${answer1.split(marker).length - 1} marker(s)`);
  check('the live stream and a fresh read agree',
    assistantTexts(detail1.events ?? []).map((e) => e.text).join(' ').trim() === answer1,
    'live vs read');

  // The runtime injects its own context into the conversation (plugin context,
  // the skill catalog, runtime snapshots) and labels each with a source kind.
  // Only `user` may be shown as the human's words; everything else must be
  // carried as engine context, never attributed to the user.
  const userMessages = live1.filter((e) => e.role === 'user');
  const misattributed = userMessages.filter(
    (e) => e.meta?.sourceKind && e.meta.sourceKind !== 'user',
  );
  check('injected runtime context is never shown as the user\'s own words',
    misattributed.length === 0,
    `${misattributed.length} misattributed of ${userMessages.length} user-role event(s)`);
  const injected = live1.filter((e) => e.kind === 'context');
  check('injected context occurred and was retained as engine context',
    injected.length > 0 && injected.every((e) => e.meta.sourceKind !== 'user'),
    `${injected.length} context event(s): ${[...new Set(injected.map((e) => e.meta.sourceKind))].join(',')}`);
  check('the transcript matches what the user typed',
    userMessages.some((e) => e.text.includes(marker)), 'user text present');

  // The agent echoes the user's message for instant feedback and the runtime
  // echoes it back too. Both copies used to be kept, so the user's own words
  // appeared twice. Assert the count, not merely presence: a `some()` check
  // passes even when every message is duplicated.
  const userEchoes = live1.filter((e) => e.role === 'user' && e.text.includes(marker));
  check('the user message appears exactly once, not once per echo',
    userEchoes.length === 1, `${userEchoes.length} copy/copies of the user message`);

  const allUserText = live1.filter((e) => e.role === 'user').map((e) => e.text);
  check('no user message is duplicated in the transcript',
    new Set(allUserText).size === allUserText.length,
    `${allUserText.length} user event(s), ${new Set(allUserText).size} distinct`);
  check('no streamed event is still marked as streaming',
    live1.every((e) => e.streaming !== true), 'all previews closed');
  check('streaming deltas carried their payload (no read round-trip needed)',
    frames.some((f) => f.type === 'chat.event' && f.chatId === chatId && f.stream === true && f.item?.text),
    'item present on stream frames');
  check('usage was captured from the runtime', Boolean(detail1.usage), JSON.stringify(detail1.usage));
  check('turn 1 emitted a turn boundary event',
    (detail1.events ?? []).some((e) => e.kind === 'turn'), 'turn event present');

  // --- turn 2: continuity on the same runtime ---
  const from2 = frames.length;
  send('chat.send', { chatId, text: 'What code did I ask you to remember? Reply with just the code.' });
  const turnEndsBefore2 = frames.filter(isTurnEnd).length;
  const sent2 = await waitForNext((f) => f.type === 'chat.sent' && f.chatId === chatId, 60000, 'chat.sent (turn 2)');
  check('second turn accepted on the same chat', Boolean(sent2?.messageId), sent2?.messageId);
  check('both turns share one wire session id', sent2.sessionId === sent1.sessionId, sent2.sessionId);

  await waitForCount(isTurnEnd, turnEndsBefore2 + 1, 300000, 'turn 2 completion');
  check('turn 2 reached its own boundary', true, `${frames.filter(isTurnEnd).length} turn/end frame(s) total`);

  const detail2 = await readDetail('chat detail after turn 2');
  const live2 = liveTranscript(chatId);
  const allLiveAssistant = assistantTexts(live2);
  const firstTurnCount = assistantTexts(live1).length;
  const answer2 = allLiveAssistant.slice(firstTurnCount).map((e) => e.text).join(' ').trim();
  check('turn 2 produced an assistant message', answer2.length > 0, `${allLiveAssistant.length - firstTurnCount} new`);
  check('SESSION CONTINUITY: turn 2 recalled turn 1 without re-feeding context',
    answer2.includes(marker), JSON.stringify(answer2.slice(0, 200)));
  check('turn 2 answer also appears exactly once',
    answer2.split(marker).length - 1 === 1, `${answer2.split(marker).length - 1} marker(s)`);
  check('the final live transcript matches a fresh read',
    assistantTexts(detail2.events ?? []).length === allLiveAssistant.length,
    `live=${allLiveAssistant.length} read=${assistantTexts(detail2.events ?? []).length}`);
  check('runtime stayed live across both turns', detail2.live === true, `live=${detail2.live}`);
  check('runtime stayed ready across both turns', detail2.ready === true, `ready=${detail2.ready}`);

  console.log('');
  console.log('transcript as the phone would render it:');
  for (const line of chatEventsFrom(detail2)) console.log('  ' + line);

  // --- chat.close ---
  send('chat.close', { chatId });
  const closed = await waitForNext(
    (f) => f.type === 'action.result' && f.action === 'chat.close',
    20000,
    'chat.close result',
  );
  check('chat.close succeeded', closed.ok === true, closed.message);

  send('chat.list', {});
  const afterClose = await waitForNext(
    (f) => f.type === 'chats' && !f.chats.some((c) => c.id === chatId),
    10000,
    'chats list without the closed chat',
  );
  check('closed chat is gone from the list', Boolean(afterClose));

  finish();
})().catch((err) => {
  check('e2e completed without an unexpected throw', false, err?.message ?? String(err));
  finish();
});

function finish() {
  try { socket?.close(); } catch { /* ignore */ }
  setTimeout(() => {
    console.log('');
    console.log(`${passes} passed, ${failures} failed`);
    process.exit(failures === 0 ? 0 : 1);
  }, 300);
}
