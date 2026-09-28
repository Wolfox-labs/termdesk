/**
 * Minimal unified-pipeline smoke: one turn on dsh, one on codex.
 * Authorized models only: wolfox/mimo-v2.6-flash and codex qwen3.8-flash.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();
const url = 'ws://127.0.0.1:7420';

function waitFor(ws, pred, ms, label) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout ${label}`)), ms);
    const onMsg = (raw) => {
      let f;
      try { f = JSON.parse(String(raw)); } catch { return; }
      if (pred(f)) {
        clearTimeout(t);
        ws.off('message', onMsg);
        resolve(f);
      }
    };
    ws.on('message', onMsg);
  });
}

async function runCase(name, createPayload, sendText) {
  const ws = new WebSocket(url);
  await new Promise((res, rej) => {
    ws.once('open', res);
    ws.once('error', rej);
  });
  ws.send(JSON.stringify({ type: 'auth', token }));
  await waitFor(ws, (f) => f.type === 'auth.ok', 8000, `${name} auth`);

  ws.send(JSON.stringify(createPayload));
  const created = await waitFor(ws, (f) => f.type === 'chat' && f.id, 8000, `${name} create`);
  const chatId = created.id;
  console.log(`[${name}] created`, {
    id: chatId,
    engine: created.engine,
    provider: created.provider,
    model: created.model,
    cwd: created.cwd,
  });

  ws.send(JSON.stringify({ type: 'chat.send', chatId, text: sendText }));
  const deadline = Date.now() + 90_000;
  /** seq -> text for assistant messages; stream previews are replaced/removed. */
  const assistantBySeq = new Map();
  const seenKinds = new Set();
  let finished = false;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} turn timeout`)), 90_000);
    ws.on('message', (raw) => {
      let f;
      try { f = JSON.parse(String(raw)); } catch { return; }
      if (f.type === 'chat.event' && f.chatId === chatId) {
        if (f.removed) {
          assistantBySeq.delete(f.seq);
          return;
        }
        const item = f.item || {};
        seenKinds.add(`${item.kind}/${item.role ?? ''}`);
        if (process.env.SMOKE_DUMP === '1') {
          console.log(`  ev seq=${f.seq} kind=${item.kind} role=${item.role} stream=${item.streaming} text=${JSON.stringify((item.text || '').slice(0, 120))}`);
        }
        if (item.kind === 'message' && item.role === 'assistant') {
          assistantBySeq.set(f.seq, item.text || '');
        }
      }
      if (f.type === 'chat.turn' && f.chatId === chatId && (f.state === 'ended' || f.state === 'failed')) {
        finished = f.state === 'ended';
        clearTimeout(timer);
        resolve();
      }
      if (Date.now() > deadline) {
        clearTimeout(timer);
        reject(new Error(`${name} deadline`));
      }
    });
  });

  const assistant = [...assistantBySeq.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, t]) => t)
    .join('');
  console.log(`[${name}] kinds=${[...seenKinds].join(',')} assistant=${JSON.stringify(assistant.slice(0, 160))}`);

  console.log(`[${name}] turn finished=${finished} assistant=${JSON.stringify(assistant.slice(0, 120))}`);
  ws.close();
  return { name, chatId, finished, assistant: assistant.slice(0, 120) };
}

const cwd = path.join(os.tmpdir(), 'termdesk-unified-smoke');
fs.mkdirSync(cwd, { recursive: true });

const a = await runCase(
  'dsh/mimo-v2.6-flash',
  {
    type: 'chat.create',
    engine: 'dsh',
    provider: 'wolfox',
    model: 'mimo-v2.6-flash',
    cwd,
    title: 'smoke-dsh',
  },
  'Reply with exactly: DSH_OK',
);

const b = await runCase(
  'codex/qwen3.8-flash',
  {
    type: 'chat.create',
    engine: 'codex',
    provider: 'aliyun',
    model: 'qwen3.8-flash',
    cwd,
    title: 'smoke-codex',
  },
  'Reply with exactly: CODEX_OK',
);

console.log(JSON.stringify({ ok: a.finished && b.finished, a, b }, null, 2));
process.exit(a.finished && b.finished ? 0 : 1);
