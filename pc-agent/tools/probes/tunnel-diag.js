/**
 * Diagnostic: watch a wss:// connection through the Cloudflare tunnel frame by
 * frame, logging arrivals, silences and closures with timestamps.
 *
 * Written because the tunnel e2e passed and then failed on a repeat run in a
 * way that could be either a stalled socket or a missed frame, and guessing
 * between those two wastes time. This prints what actually happens on the wire.
 *
 * Usage: node tools/tunnel-diag.js [hostname] [seconds]
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const host = process.env.TERMDESK_TUNNEL_HOST || process.argv[2] || '';
if (!host) {
  console.error('usage: set TERMDESK_TUNNEL_HOST or pass the hostname, e.g. term.example.com');
  process.exit(2);
}
const seconds = Number(process.argv[3] || '60');
const started = Date.now();

const t = () => `+${String(((Date.now() - started) / 1000).toFixed(2)).padStart(7)}s`;
const log = (msg) => console.log(`${t()}  ${msg}`);

const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

log(`connecting to wss://${host}`);
const ws = new WebSocket(`wss://${host}`, { handshakeTimeout: 30000 });

let lastFrameAt = Date.now();
let frames = 0;

setInterval(() => {
  const silence = Date.now() - lastFrameAt;
  if (silence > 5000) log(`[silence] no frames for ${(silence / 1000).toFixed(1)}s  (total frames ${frames})  readyState=${ws.readyState}`);
}, 2000);

ws.on('open', () => {
  log('OPEN  (upgrade through Cloudflare succeeded)');
  log('--> auth');
  ws.send(JSON.stringify({ v: 1, type: 'auth', token }));
});

ws.on('message', (raw) => {
  lastFrameAt = Date.now();
  frames += 1;
  let f;
  try {
    f = JSON.parse(raw.toString());
  } catch {
    log(`<-- [non-JSON ${raw.length}B] ${raw.toString().slice(0, 80)}`);
    return;
  }
  const detail = f.type === 'chat'
    ? `id=${f.id} events=${Array.isArray(f.events) ? f.events.length : 'none'}`
    : f.type === 'chat.event'
      ? `chatId=${f.chatId} seq=${f.seq} item=${f.item?.kind ?? '-'}/${f.item?.text?.length ?? 0}B`
      : '';
  log(`<-- ${f.type} ${detail}`);

  if (f.type === 'auth.ok') {
    log('--> chat.create');
    ws.send(JSON.stringify({ v: 1, type: 'chat.create', cwd: process.cwd(), title: 'diag' }));
  }
  if (f.type === 'chat' && typeof f.id === 'string' && f.events === undefined) {
    const chatId = f.id;
    log(`--> chat.send (chatId=${chatId})`);
    ws.send(JSON.stringify({
      v: 1,
      type: 'chat.send',
      chatId,
      text: 'Reply with exactly: DIAG-OK',
    }));
    setTimeout(() => {
      log('--> chat.read');
      ws.send(JSON.stringify({ v: 1, type: 'chat.read', chatId }));
    }, 20000);
  }
});

ws.on('ping', () => log('PING from server'));
ws.on('pong', () => log('PONG from server'));

ws.on('close', (code, reason) => {
  log(`CLOSE code=${code} reason="${reason?.toString() ?? ''}"`);
});

ws.on('error', (err) => log(`ERROR ${err.message}`));

setTimeout(() => {
  log('--- window elapsed, closing ---');
  try { ws.close(1000, 'diag done'); } catch { /* ignore */ }
  setTimeout(() => process.exit(0), 800);
}, seconds * 1000);
