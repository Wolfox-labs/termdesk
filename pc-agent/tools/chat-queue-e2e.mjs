/**
 * A message typed while the answer is still coming waits on the PC and then goes.
 *
 * The product decision this drives: the phone is a display shell, a kernel takes one
 * prompt at a time, and the queue lives on this side. The unit test
 * (`tools/chat-queue-test.js`) pins the manager's rules with a stubbed dispatch; what it
 * cannot pin is the wire — that the message is accepted rather than refused, that the line
 * reaches the phone straight away marked as waiting, and that the marker comes OFF when
 * the message actually goes, because a marker that sticks would sit on a message the
 * kernel has already seen.
 *
 * A stub kernel does the answering, so this costs no model call.
 *
 *   node tools/chat-queue-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_QUEUE_E2E_PORT ?? 7449);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

let failures = 0;
const check = (label, ok, detail = '') => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `  [${detail}]` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const env = {
  ...process.env,
  TERMDESK_ACP_KERNELS: JSON.stringify([
    {
      id: 'stub',
      label: 'Stub kernel (free)',
      bin: process.execPath,
      args: [path.join(ROOT, 'tools', 'fake-acp-agent.mjs')],
    },
  ]),
};

const server = spawn(
  process.execPath,
  ['src/server.js', '--host', '127.0.0.1', '--port', String(PORT)],
  { cwd: ROOT, env, stdio: ['ignore', 'pipe', 'pipe'] },
);
let serverLog = '';
server.stdout.on('data', (d) => { serverLog += d.toString(); });
server.stderr.on('data', (d) => { serverLog += d.toString(); });

const shutdown = (code) => {
  try { server.kill(); } catch { /* already gone */ }
  process.exit(code);
};

async function waitForHealth(ms = 15000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/healthz`);
      if (res.ok) return true;
    } catch { /* not up yet */ }
    await sleep(100);
  }
  return false;
}

async function connect() {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  const client = { ws, frames: [] };
  ws.on('message', (raw) => client.frames.push(JSON.parse(raw.toString())));
  await new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });
  return client;
}

/** A chat.event frame carries the transcript record under `item` (see ChatManager.emitEvent). */
const recordOf = (frame) => frame.item ?? frame.event ?? frame;

/** Answer any permission the stub kernel asks, so a turn can finish. */
function answerApprovals(client, chatId, answered) {
  for (const frame of client.frames) {
    if (frame.type !== 'chat.approval' || frame.chatId !== chatId) continue;
    if (frame.state === 'resolved' || !frame.requestId || answered.has(frame.requestId)) continue;
    answered.add(frame.requestId);
    const option = (frame.options ?? []).find((o) => o.id === 'allow_once') ?? (frame.options ?? [])[0];
    if (option) {
      client.ws.send(JSON.stringify({ type: 'chat.approve', requestId: frame.requestId, optionId: option.id }));
    }
  }
}

try {
  check('server starts', await waitForHealth());
  const client = await connect();
  client.ws.send(JSON.stringify({ type: 'auth', token: TOKEN }));
  const authed = Date.now() + 8000;
  while (Date.now() < authed && !client.frames.some((f) => f.type === 'auth.ok')) await sleep(50);
  check('the client authenticates', client.frames.some((f) => f.type === 'auth.ok'));

  client.ws.send(JSON.stringify({ type: 'chat.create', engine: 'stub', cwd: ROOT.replace(/\\/g, '/') }));
  const created = Date.now() + 15000;
  while (Date.now() < created && !client.frames.some((f) => f.type === 'chat' && f.id)) await sleep(50);
  const chatId = client.frames.find((f) => f.type === 'chat' && f.id)?.id;
  check('a conversation is created on the stub kernel', Boolean(chatId), String(chatId));

  const answered = new Set();
  const pump = setInterval(() => answerApprovals(client, chatId, answered), 50);

  // ---- the first message starts a turn --------------------------------------
  const FIRST = 'first question';
  const QUEUED = 'the one typed while it was answering';
  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: FIRST, requestId: 'r-1' }));

  // The second message must arrive WHILE the first turn is running, and that is a narrow
  // window: on the ACP path `chat.send` is answered when the turn ENDS (the kernel resolves
  // `session/prompt` at the end of it), so waiting for the first acknowledgement would put
  // this message after the turn and quietly test the ordinary path instead of the queue.
  // 150 ms is inside the stub kernel's turn (it streams for ~300 ms).
  await sleep(150);

  const mark = client.frames.length;
  const queuedSentAt = Date.now();
  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: QUEUED, requestId: 'r-2' }));

  const ackAt = Date.now() + 20000;
  let ack = null;
  while (Date.now() < ackAt) {
    ack = client.frames.slice(mark).find((f) => f.requestId === 'r-2' && (f.type === 'chat.sent' || f.type === 'action.result'));
    if (ack) break;
    await sleep(25);
  }
  check('a message typed during a turn is accepted instead of refused',
    ack?.type === 'chat.sent', JSON.stringify(ack && { type: ack.type, code: ack.code }));
  // Accepted BEFORE the turn it interrupted had ended — which is what makes it a queue and
  // not just a message that happened to be sent later.
  const endedBeforeAck = client.frames
    .slice(0, mark + client.frames.slice(mark).findIndex((f) => f === ack))
    .some((f) => f.type === 'chat.turn' && f.state === 'ended');
  check('and it is accepted while the turn is still running, not after it',
    ack !== null && !endedBeforeAck, `answered after ${Date.now() - queuedSentAt} ms`);

  // The line must be on screen NOW, marked as waiting: a message that disappears until it
  // is sent reads as a message that was lost.
  const emissions = () => client.frames
    .filter((f) => f.type === 'chat.event')
    .map(recordOf)
    .filter((r) => r.kind === 'message' && r.role === 'user' && r.text === QUEUED);
  check('the waiting message is shown immediately', emissions().length >= 1, `${emissions().length} emissions`);
  check('and it says it is waiting', emissions()[0]?.queued === true, JSON.stringify(emissions()[0]));

  // ---- the turn ends, the queued message goes -------------------------------
  const endedAt = Date.now() + 30000;
  while (Date.now() < endedAt && !client.frames.some((f) => f.type === 'chat.turn' && f.state === 'ended')) {
    await sleep(50);
  }
  check('the first turn ends', client.frames.some((f) => f.type === 'chat.turn' && f.state === 'ended'));

  const clearedAt = Date.now() + 20000;
  while (Date.now() < clearedAt && !emissions().some((r) => r.queued === false)) await sleep(50);
  check('the message stops saying it is waiting once it actually goes',
    emissions().some((r) => r.queued === false), JSON.stringify(emissions().map((r) => ({ seq: r.seq, queued: r.queued }))));

  // The duplicate this design exists to avoid: the same words drawn as two lines.
  const seqs = new Set(emissions().map((r) => r.seq));
  check('and it is still ONE line, not a second copy of the same words',
    seqs.size === 1, `seqs=${[...seqs].join(',')}`);

  // The queued message really was sent to the kernel: a second turn ended after it.
  const twoTurns = Date.now() + 30000;
  while (Date.now() < twoTurns
    && client.frames.filter((f) => f.type === 'chat.turn' && f.state === 'ended').length < 2) {
    await sleep(50);
  }
  check('the queued message was actually sent, and answered',
    client.frames.filter((f) => f.type === 'chat.turn' && f.state === 'ended').length >= 2,
    `${client.frames.filter((f) => f.type === 'chat.turn' && f.state === 'ended').length} turns ended`);

  clearInterval(pump);
  client.ws.close();
} catch (err) {
  check('the queue run completed', false, err.message);
  console.error(serverLog.slice(-1500));
}

console.log(failures === 0 ? '\nchat queue e2e: all checks passed' : `\nchat queue e2e: ${failures} failed`);
shutdown(failures === 0 ? 0 : 1);
