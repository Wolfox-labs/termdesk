/**
 * A message typed while the answer is still coming waits its turn on the PC.
 *
 * The product decision: the phone is a display shell, the kernel takes one prompt at a
 * time, and the queue belongs on this side. Refusing with "上一轮还在进行中" put the waiting
 * on the person instead, and a second phone (or the same one after a reconnect) would hit
 * that refusal on a conversation it was perfectly entitled to continue.
 *
 * What this file is really guarding is the duplicate. The line is drawn the moment it is
 * typed — a message that disappears until later reads as a message that was lost — and it
 * must NOT be drawn again when its turn comes. That is why the queue stores the transcript
 * seq: the dispatcher gets an existing line, not a new one, and `pendingUserEcho` (which
 * means "the kernel has not echoed this line yet") is only set when the message actually
 * starts.
 *
 * No kernel is involved: the dispatch is stubbed, so this costs nothing and runs anywhere.
 *
 *   node tools/chat-queue-test.js
 */
import os from 'node:os';
import path from 'node:path';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const { ChatManager, Chat } = await import('../src/chat.js');

const manager = new ChatManager();
const chat = new Chat({ id: 'c1', title: 't', cwd: os.tmpdir(), engine: 'dsh', provider: null, model: null });
chat.status = 'running';
manager.chats.set(chat.id, chat);

const dispatched = [];
let failNext = false;
manager.dispatchTurn = (target, text, seq) => {
  dispatched.push({ text, seq });
  if (failNext) { failNext = false; return { ok: false, code: 'spawn_failed', message: '运行时没了' }; }
  return { ok: true, userSeq: seq };
};

const linesWith = (text) => chat.events.filter((e) => e.text === text);

// --- queueing ---------------------------------------------------------------
check('the constructor gives every chat an empty queue',
  Array.isArray(chat.queue) && chat.queue.length === 0);

const first = await manager.send(chat.id, 'while you are at it, also check the tests');
check('a message typed during a turn is accepted rather than refused',
  first.ok === true && first.queued === true, JSON.stringify(first));
check('and it says where it is in line', first.position === 1, String(first.position));
check('it is shown immediately, once', linesWith('while you are at it, also check the tests').length === 1);
check('the shown line is marked as waiting',
  linesWith('while you are at it, also check the tests')[0].queued === true);
check('nothing was dispatched yet', dispatched.length === 0, JSON.stringify(dispatched));
check('the echo slot is untouched while it waits', chat.pendingUserEcho === null,
  String(chat.pendingUserEcho));

// The cap: five is "the next thing", not a batch interface.
let capMessage = null;
for (let i = 1; i <= 6; i += 1) {
  const r = await manager.send(chat.id, `queued ${i}`);
  if (r.ok !== true) capMessage = r;
}
check('the queue has a ceiling and says so', capMessage?.code === 'queue_full', JSON.stringify(capMessage));
check('the ceiling is real', chat.queue.length === 5, String(chat.queue.length));

// --- draining ---------------------------------------------------------------
chat.status = 'running';
check('draining while the turn is still running does nothing',
  (await manager.drainQueue(chat)) === false);

chat.status = 'idle';
const drained = await manager.drainQueue(chat);
check('a free conversation starts the message that waited', drained === true);
check('it starts the OLDEST one first', dispatched[0]?.text === 'while you are at it, also check the tests',
  dispatched[0]?.text);
check('the message is not drawn a second time',
  linesWith('while you are at it, also check the tests').length === 1);
check('the echo slot now points at the line that was already shown',
  chat.pendingUserEcho === first.userSeq, `slot=${chat.pendingUserEcho?.seq} line=${first.userSeq?.seq}`);
check('and the line stops saying it is waiting, because it is not any more',
  linesWith('while you are at it, also check the tests')[0].queued === false);
check('the queue moved on', chat.queue.length === 4, String(chat.queue.length));
check('and the conversation is marked busy again', chat.status === 'running', chat.status);

// --- a queued message that cannot start -------------------------------------
chat.status = 'idle';
failNext = true;
const before = dispatched.length;
await manager.drainQueue(chat);
check('a failure is reported rather than swallowed',
  chat.events.some((e) => e.kind === 'error' && /排队中的消息没有发出/.test(e.text)),
  chat.events.filter((e) => e.kind === 'error').map((e) => e.text).join(' | '));
check('and the queue keeps moving instead of stalling behind it',
  dispatched.length === before + 2, `dispatched=${dispatched.length}`);
check('a stalled queue does not leave the chat stuck as busy',
  chat.status === 'running' || chat.status === 'idle', chat.status);

// --- stopping ---------------------------------------------------------------
chat.status = 'running';
const queuedNow = chat.queue.length;
const stopResult = manager.stopTurn(chat, '已停止本轮回复');
check('stopping works', stopResult.ok === true);
check('stopping empties the queue', chat.queue.length === 0, String(chat.queue.length));
check('and says how many messages will not be sent',
  chat.events.some((e) => /队列中的 \d+ 条消息未发送/.test(e.text)),
  chat.events.filter((e) => /未发送/.test(e.text)).map((e) => e.text).join(' | '));
check('the words themselves are kept, not deleted',
  chat.events.some((e) => e.text === 'queued 1'), `queue had ${queuedNow}`);

manager.disposeAll();
const failed = results.filter((r) => !r.passed);
console.log(`\nChat queue: ${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length === 0 ? 0 : 1);
