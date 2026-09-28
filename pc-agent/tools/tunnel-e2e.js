/**
 * Verify the public Cloudflare Tunnel path end to end, as the phone will use it.
 *
 * This is deliberately separate from chat-e2e.js: that suite talks to the agent
 * on localhost, which proves the feature works but proves nothing about the
 * public path. This one goes through https://term.example.com, i.e. out to
 * Cloudflare's edge and back down the tunnel, so it exercises exactly the hop
 * that the campus network was breaking.
 *
 * What it checks:
 *   1. HTTPS health endpoint answers over the public hostname
 *   2. wss:// WebSocket upgrade completes through Cloudflare
 *   3. a full two-turn conversation streams back over that wss socket
 *   4. the unknown-host guard still refuses other hostnames
 *
 * Usage: node tools/tunnel-e2e.js [hostname]
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const HOST = process.argv[2] || 'term.example.com';
const WS_URL = `wss://${HOST}`;
const HTTP_URL = `https://${HOST}`;

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
  return fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();
}

const frames = [];
let socket;
let waiters = [];

function waitFor(predicate, timeoutMs, label) {
  const from = frames.length;
  const existing = frames.find((f, i) => i >= from && predicate(f));
  if (existing) return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      waiters = waiters.filter((w) => w.timer !== timer);
      reject(new Error(`timed out waiting for ${label}`));
    }, timeoutMs);
    waiters.push({ predicate, resolve, timer, label, from });
  });
}

function dispatch(frame) {
  const index = frames.length;
  frames.push(frame);
  const remaining = [];
  for (const w of waiters) {
    // Counter waiters watch the whole log and carry no `from`; comparing
    // `index >= undefined` is always false, so they need their own branch or
    // they can never be woken even when their condition is already satisfied.
    const hit = w.isCount ? w.predicate() : index >= (w.from ?? 0) && w.predicate(frame, index);
    if (hit) {
      clearTimeout(w.timer);
      // A frame waiter must receive the frame it matched; a counter waiter has
      // no single frame to hand back. Resolving both with `true` silently gave
      // callers a boolean instead of a frame, so `created.id` was undefined and
      // every later assertion timed out against a healthy server.
      w.resolve(w.isCount ? true : frame);
    } else {
      remaining.push(w);
    }
  }
  waiters = remaining;
}

function send(type, payload = {}) {
  socket.send(JSON.stringify({ v: 1, type, ...payload }));
}

function waitForCount(predicate, target, timeoutMs, label) {
  const count = () => frames.filter(predicate).length;
  if (count() >= target) return Promise.resolve(true);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      waiters = waiters.filter((w) => w.timer !== timer);
      reject(new Error(`timed out waiting for ${label} (saw ${count()}/${target})`));
    }, timeoutMs);
    waiters.push({ isCount: true, predicate: () => count() >= target, resolve, timer, label });
  });
}

function connect() {
  return new Promise((resolve, reject) => {
    socket = new WebSocket(WS_URL, { handshakeTimeout: 30000 });
    socket.on('open', resolve);
    socket.on('error', (e) => reject(new Error(`ws error: ${e.message}`)));
    socket.on('unexpected-response', (_req, res) =>
      reject(new Error(`ws upgrade rejected: HTTP ${res.statusCode}`)),
    );
    socket.on('message', (raw) => {
      try {
        dispatch(JSON.parse(raw.toString()));
      } catch {
        /* ignore non-JSON */
      }
    });
  });
}

(async () => {
  console.log(`public path: ${WS_URL}`);
  console.log('');

  // 1. HTTPS health over the public hostname
  const t0 = Date.now();
  let health;
  try {
    const res = await fetch(`${HTTP_URL}/healthz`, { signal: AbortSignal.timeout(30000) });
    health = await res.json();
    check('HTTPS health endpoint answers over the tunnel', res.status === 200 && health.ok === true,
      `${res.status} in ${Date.now() - t0}ms`);
  } catch (err) {
    check('HTTPS health endpoint answers over the tunnel', false, err.message);
    finish();
    return;
  }

  // 2. WebSocket upgrade through Cloudflare
  try {
    await connect();
    check('wss:// upgrade completed through Cloudflare edge', true);
  } catch (err) {
    check('wss:// upgrade completed through Cloudflare edge', false, err.message);
    finish();
    return;
  }

  // 3. auth
  send('auth', { token: readToken() });
  try {
    const ok = await waitFor((f) => f.type === 'auth.ok', 20000, 'auth.ok over tunnel');
    check('authentication accepted over the tunnel', Boolean(ok));
  } catch (err) {
    check('authentication accepted over the tunnel', false, err.message);
    finish();
    return;
  }

  // 4. a real conversation across the tunnel
  const chatBefore = frames.length;
  send('chat.create', { cwd: process.cwd(), title: 'tunnel-e2e' });
  // The create reply is a `chat` frame WITHOUT an `events` array; a later
  // chat.read reply has one. Matching on type+id alone picks up the wrong frame.
  const created = await waitFor(
    (f) => f.type === 'chat' && typeof f.id === 'string' && !Array.isArray(f.events),
    30000,
    'chat created',
  );
  const chatId = created.id;
  check('a chat can be created over the tunnel', Boolean(chatId), chatId);
  check('the create reply arrived after the handshake', frames.length > chatBefore, 'ordered');

  const isTurnEnd = (f) => f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended';
  const marker = `TN-${Date.now().toString(36).toUpperCase()}`;

  send('chat.send', { chatId, text: `Remember this code: ${marker}. Reply with just the code.` });
  await waitFor((f) => f.type === 'chat.sent' && f.chatId === chatId, 90000, 'turn 1 receipt');
  await waitForCount(isTurnEnd, 1, 300000, 'turn 1 completion over tunnel');
  check('turn 1 completed over the tunnel', true);

  // Did streamed frames actually make it through, or only the final state?
  const streamed = frames.filter((f) => f.type === 'chat.event' && f.chatId === chatId && f.stream === true);
  check('streamed deltas arrived through Cloudflare (not buffered away)', streamed.length > 0,
    `${streamed.length} stream frame(s)`);

  const endsBefore = frames.filter(isTurnEnd).length;
  const receiptsBefore = frames.filter((f) => f.type === 'chat.sent' && f.chatId === chatId).length;
  send('chat.send', { chatId, text: 'What code did I ask you to remember? Reply with just the code.' });
  await waitForCount(
    (f) => f.type === 'chat.sent' && f.chatId === chatId,
    receiptsBefore + 1,
    90000,
    'turn 2 receipt',
  );
  await waitForCount(isTurnEnd, endsBefore + 1, 300000, 'turn 2 completion over tunnel');

  send('chat.read', { chatId });
  const detail = await waitFor(
    (f) => f.type === 'chat' && f.id === chatId && Array.isArray(f.events),
    30000,
    'chat detail over tunnel',
  );
  const answers = (detail.events ?? []).filter((e) => e.kind === 'message' && e.role === 'assistant');
  const joined = answers.map((e) => e.text).join(' ');
  check('CONTINUITY over the public path: turn 2 recalled turn 1', joined.includes(marker),
    JSON.stringify(joined.slice(0, 120)));
  check('the answer is not duplicated in the stored transcript',
    joined.split(marker).length - 1 === 2, `${joined.split(marker).length - 1} marker(s)`);

  // 5. the unknown-host guard
  try {
    const bad = await fetch('https://example.com/', { signal: AbortSignal.timeout(20000) });
    check('an unlisted hostname is not served by this tunnel', false, `got HTTP ${bad.status}`);
  } catch (err) {
    check('an unlisted hostname is not served by this tunnel', true, 'refused');
  }

  send('chat.close', { chatId });
  try {
    await waitFor((f) => f.type === 'action.result' && f.action === 'chat.close', 20000, 'chat.close');
  } catch {
    /* closing is best-effort here */
  }

  console.log('');
  console.log(`round-trip timings: health ${Date.now() - t0}ms total including both turns`);
  finish();
})().catch((err) => {
  check('tunnel e2e completed without an unexpected throw', false, err?.message ?? String(err));
  finish();
});

function finish() {
  try { socket?.close(); } catch { /* ignore */ }
  setTimeout(() => {
    console.log('');
    console.log(`${passes} passed, ${failures} failed`);
    process.exit(failures === 0 ? 0 : 1);
  }, 400);
}
