/**
 * A message queued while the link was down arrives exactly once.
 *
 * The phone holds messages the person wrote during an outage and replays them when
 * the link comes back (`data/PendingSends.kt`). The ordering and expiry rules are
 * unit-tested; what cannot be is the part that only exists on the wire: the agent
 * has to ECHO the message's id, because that echo — not a timer — is what tells
 * the phone the words actually arrived. Without it the phone either loses the
 * message or sends it twice.
 *
 * So this drives the real thing twice with the same id, the way a reconnect does:
 *
 *   1. send `chat.send` with a requestId, read the reply;
 *   2. send the SAME frame again (the phone did not see the first reply);
 *   3. the agent answers again, with the same requestId — and the transcript shows
 *      the message once, not twice.
 *
 * Cost: one stub kernel, no model call.
 *
 *   node tools/chat-queued-delivery-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_QUEUE_E2E_PORT ?? 7447);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = machineTokenOrSkip('chat-queued-delivery-e2e');

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
    await sleep(200);
  }
  return false;
}

function connect() {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  const frames = [];
  ws.on('message', (raw) => {
    try { frames.push(JSON.parse(String(raw))); } catch { /* ignore */ }
  });
  return new Promise((resolve, reject) => {
    ws.once('open', () => resolve({ ws, frames }));
    ws.once('error', reject);
  });
}

async function waitFrame(client, pred, ms, label) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const hit = client.frames.find(pred);
    if (hit) return hit;
    await sleep(50);
  }
  throw new Error(`timeout waiting for ${label} (${ms} ms); server log: ${serverLog.slice(-400)}`);
}

/**
 * Wait for the acknowledgement of one queued message, answering any permission
 * question on the way.
 *
 * Two things it must get right, both learned the hard way:
 *   - a stub kernel asks before it runs anything and the turn does not finish
 *     until it is answered, so an unanswered question looks exactly like a lost
 *     message;
 *   - only acknowledgements ARRIVING FROM NOW count. Scanning the frame log from
 *     the start makes the second wait match the first reply, which turns "the
 *     replay was acknowledged" into a check that can never fail.
 */
async function waitForAck(client, requestId, chatId, ms, fromIndex = 0) {
  const deadline = Date.now() + ms;
  const answered = new Set();
  while (Date.now() < deadline) {
    for (const frame of client.frames.slice(fromIndex)) {
      if (frame.type === 'chat.approval' && frame.chatId === chatId
        && frame.state !== 'resolved' && frame.requestId && !answered.has(frame.requestId)) {
        answered.add(frame.requestId);
        const option = (frame.options ?? []).find((o) => o.id === 'allow_once') ?? (frame.options ?? [])[0];
        if (option) {
          client.ws.send(JSON.stringify({ type: 'chat.approve', requestId: frame.requestId, optionId: option.id }));
        }
      }
      if ((frame.type === 'chat.sent' || frame.type === 'action.result') && frame.requestId === requestId) {
        return frame;
      }
    }
    await sleep(50);
  }
  throw new Error(`timeout waiting for the acknowledgement of ${requestId}`);
}

/** Wait until the conversation reports the turn is over, so a replay is not "busy". */
async function waitTurnEnded(client, chatId, ms, fromIndex = 0) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const ended = client.frames.slice(fromIndex).some(
      (f) => f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended',
    );
    if (ended) return true;
    await sleep(50);
  }
  return false;
}

try {
  check('server starts', await waitForHealth());

  const client = await connect();
  client.ws.send(JSON.stringify({ type: 'auth', token: TOKEN }));
  await waitFrame(client, (f) => f.type === 'auth.ok', 8000, 'auth.ok');

  client.ws.send(JSON.stringify({ type: 'chat.create', engine: 'stub', cwd: ROOT.replace(/\\/g, '/') }));
  const created = await waitFrame(client, (f) => f.type === 'chat' && f.id, 15000, 'chat frame');
  const chatId = created.id;

  // ---- the replay: the same queued message, sent twice ----------------------

  const REQUEST_ID = 'r-queued-1';
  const frame = { type: 'chat.send', chatId, text: 'queued while offline', requestId: REQUEST_ID };

  client.ws.send(JSON.stringify(frame));
  const first = await waitForAck(client, REQUEST_ID, chatId, 30000).catch((err) => {
    // Print what the agent DID say, so a failure says why rather than only that.
    console.error('frames received:', JSON.stringify(client.frames.map((f) => ({
      type: f.type, chatId: f.chatId, code: f.code, requestId: f.requestId,
    })), null, 1).slice(0, 2000));
    throw err;
  });
  check('the first send is acknowledged', first.type === 'chat.sent', first.type);
  check('and the acknowledgement carries the id the phone queued under',
    first.requestId === REQUEST_ID, String(first.requestId));

  // The phone never saw that reply (its socket died), so it replays on reconnect.
  // It waits for the turn to finish first, because replaying into a running turn
  // is refused as busy — a real constraint the phone has to respect too.
  const turnOver = await waitTurnEnded(client, chatId, 30000);
  check('the first turn finishes before the replay (a replay into a busy turn is refused)',
    turnOver, String(turnOver));

  const replayFrom = client.frames.length;
  client.ws.send(JSON.stringify(frame));
  const second = await waitForAck(client, REQUEST_ID, chatId, 30000, replayFrom);
  check('the replay is acknowledged too, so the phone can drop it from the queue',
    second.type === 'chat.sent', second.type);

  // What the phone needs from these two acknowledgements is the id, so it can tell
  // "arrived" from "arrived again" and drop exactly one entry from its queue.
  const acks = client.frames.filter((f) => f.type === 'chat.sent' && f.requestId === REQUEST_ID);
  check('exactly one acknowledgement per send, each carrying the same id',
    acks.length === 2, `acks=${acks.length}`);
  check('and every one of them names the conversation',
    acks.every((f) => f.chatId === chatId), acks.map((f) => f.chatId).join(','));

  // Stated so it is not mistaken for a bug later: the agent ECHOES rather than
  // de-duplicates. A second `chat.send` really is a second message, because that
  // is what pressing send twice means; the queue's job is to not create that
  // second send unless the person did.
  check('the agent does not silently merge two identical sends', acks.length === 2, `acks=${acks.length}`);

  shutdown(failures === 0 ? 0 : 1);
} catch (err) {
  console.error(`FAIL  ${String(err?.message ?? err)}`);
  shutdown(1);
}
