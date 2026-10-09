/**
 * News that happens while nobody is attached is delivered when the phone comes back.
 *
 * The unit test pins the Notifier's rules with an injected clock and a fake socket. What it
 * cannot pin is the wiring, and the wiring is where this feature is easy to get subtly
 * wrong: `notifier.attach` has to happen at authentication, `notifier.detach` at close, and
 * anything that happened in between has to still be there when the next client arrives —
 * marked `whileAway`, because "your turn finished" and "your turn finished ten minutes ago"
 * are different sentences.
 *
 * Three clients, one stub kernel, no model call:
 *
 *   A  attaches, runs a turn, and is told about it live;
 *   B  attaches, starts a turn, and disconnects before it finishes — so the news has
 *      nowhere to go;
 *   C  attaches and must be handed exactly that news, once.
 *
 *   node tools/notify-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_NOTIFY_E2E_PORT ?? 7451);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = machineTokenOrSkip('notify-e2e');

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
  const client = { ws, frames: [], answered: new Set() };
  ws.on('message', (raw) => client.frames.push(JSON.parse(raw.toString())));
  await new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });
  client.ws.send(JSON.stringify({ type: 'auth', token: TOKEN }));
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline && !client.frames.some((f) => f.type === 'auth.ok')) await sleep(25);
  return client;
}

/** The stub kernel asks before it acts; unanswered, its turn never ends. */
function pump(client) {
  const timer = setInterval(() => {
    for (const frame of client.frames) {
      if (frame.type !== 'chat.approval' || frame.state === 'resolved') continue;
      if (!frame.requestId || client.answered.has(frame.requestId)) continue;
      client.answered.add(frame.requestId);
      const option = (frame.options ?? []).find((o) => o.id === 'allow_once') ?? (frame.options ?? [])[0];
      if (option) {
        client.ws.send(JSON.stringify({ type: 'chat.approve', requestId: frame.requestId, optionId: option.id }));
      }
    }
  }, 25);
  return () => clearInterval(timer);
}

async function createChat(client) {
  client.ws.send(JSON.stringify({ type: 'chat.create', engine: 'stub', cwd: ROOT.replace(/\\/g, '/') }));
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline && !client.frames.some((f) => f.type === 'chat' && f.id)) await sleep(25);
  return client.frames.find((f) => f.type === 'chat' && f.id)?.id ?? null;
}

async function waitForTurnEnd(client, ms = 30000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline
    && !client.frames.some((f) => f.type === 'chat.turn' && f.state === 'ended')) {
    await sleep(25);
  }
}

try {
  check('server starts', await waitForHealth());

  // ---- A: attached, told live ----------------------------------------------
  const a = await connect();
  check('the first client authenticates', a.frames.some((f) => f.type === 'auth.ok'));
  const stopA = pump(a);
  const chatA = await createChat(a);
  check('a conversation is created', Boolean(chatA), String(chatA));
  a.ws.send(JSON.stringify({ type: 'chat.send', chatId: chatA, text: 'first', requestId: 'a-1' }));
  await waitForTurnEnd(a);
  stopA();
  check('a client that is attached is told about the finished turn',
    a.frames.some((f) => f.type === 'notify' && f.kind === 'turn_done' && f.chatId === chatA),
    a.frames.filter((f) => f.type === 'notify').map((f) => f.kind).join(','));
  const liveNote = a.frames.find((f) => f.type === 'notify' && f.chatId === chatA);
  check('and it is not labelled as having waited', liveNote?.whileAway === undefined,
    JSON.stringify(liveNote?.whileAway));
  a.ws.close();
  await sleep(300);

  // ---- B: starts a turn and leaves before it ends --------------------------
  const b = await connect();
  const stopB = pump(b);
  const chatB = await createChat(b);
  b.ws.send(JSON.stringify({ type: 'chat.send', chatId: chatB, text: 'while nobody is looking', requestId: 'b-1' }));
  await sleep(150);
  check('the second client started a turn before leaving',
    b.frames.some((f) => f.type === 'chat.sent' || f.type === 'chat.status'),
    b.frames.filter((f) => f.type === 'chat.status').map((f) => f.status).join(','));
  b.ws.close();
  stopB();
  // Let that turn finish with nobody attached — the case the whole feature is for.
  await sleep(3000);

  // ---- C: comes back -------------------------------------------------------
  const c = await connect();
  await sleep(500);
  const held = c.frames.filter((f) => f.type === 'notify');
  check('coming back delivers what happened while away', held.length >= 1, `${held.length} notifications`);
  check('and it is the turn that ran with nobody attached',
    held.some((f) => f.chatId === chatB), held.map((f) => f.chatId).join(','));
  check('it is labelled as having happened while away',
    held.every((f) => f.whileAway === true), JSON.stringify(held.map((f) => f.whileAway)));
  check('it carries the conversation title and what was said',
    held.some((f) => typeof f.title === 'string' && f.title.length > 0),
    JSON.stringify(held.map((f) => ({ title: f.title, text: (f.text ?? '').slice(0, 24) }))));

  // ---- and only once -------------------------------------------------------
  const d = await connect();
  await sleep(500);
  check('a later connection is not handed the same news again',
    d.frames.filter((f) => f.type === 'notify').length === 0,
    `${d.frames.filter((f) => f.type === 'notify').length} notifications`);

  c.ws.close();
  d.ws.close();
} catch (err) {
  check('the notification run completed', false, err.message);
  console.error(serverLog.slice(-1500));
}

console.log(failures === 0 ? '\nnotify e2e: all checks passed' : `\nnotify e2e: ${failures} failed`);
shutdown(failures === 0 ? 0 : 1);
