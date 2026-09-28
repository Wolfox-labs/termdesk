/**
 * Exercise the P2 file flow against the *running* agent on the real machine,
 * mirroring exactly what the Android client sends. Read-only outside the
 * scratch directory it creates and removes.
 *
 *   node tools/p2-e2e.js
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

const SCRATCH = path.join(__dirname, '..', '..', '.tmp', 'termdesk-e2e');
fs.mkdirSync(SCRATCH, { recursive: true });

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` 鈥?${detail}` : ''}`);
};

const ws = new WebSocket(`ws://${HOST}:${PORT}`);
const pending = new Map();

ws.on('message', (raw) => {
  const frame = JSON.parse(raw.toString());
  if (frame.type === 'auth.ok') return;
  const waiter = pending.get(frame.type);
  if (waiter) {
    pending.delete(frame.type);
    waiter(frame);
  }
});

const request = (type, payload, expect) =>
  new Promise((resolve, reject) => {
    const want = expect ?? type;
    const timer = setTimeout(() => {
      pending.delete(want);
      reject(new Error(`timeout waiting for ${want}`));
    }, 30000);
    pending.set(want, (frame) => {
      clearTimeout(timer);
      resolve(frame);
    });
    ws.send(JSON.stringify({ type, ...payload }));
  });

try {
  await new Promise((r) => ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'auth', token }));
    setTimeout(r, 800);
  }));

  // 1. The app's default start path must be listable end to end.
  const home = await request('fs.list', { path: SCRATCH }, 'fs.listing');
  check('lists the scratch directory over the live agent', Array.isArray(home.items), `${home.items.length} items`);
  check('listing reports its own path', home.path === SCRATCH, home.path);

  // 2. Create, write, read back.
  await request('fs.mkdir', { path: SCRATCH, name: 'e2e.txt', kind: 'file' }, 'action.result');
  const payload = 'TermDesk P2 绔埌绔痋nline two\n';
  const wrote = await request('fs.write', { path: path.join(SCRATCH, 'e2e.txt'), text: payload }, 'action.result');
  check('writes a file through the live agent', wrote.ok === true, wrote.message);

  const read = await request('fs.read', { path: path.join(SCRATCH, 'e2e.txt') }, 'fs.file');
  check('reads back identical content', read.text === payload, JSON.stringify(read.text));

  // 3. HTTP download of the same file (the path the app uses for transfers).
  const dl = await fetch(
    `http://${HOST}:${PORT}/download?path=${encodeURIComponent(path.join(SCRATCH, 'e2e.txt'))}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  const body = await dl.text();
  check('HTTP download returns the same bytes', body === payload, `${body.length} chars`);

  // 4. Upload via HTTP, then confirm it appears in a fresh listing.
  const upBytes = Buffer.from('uploaded from phone 涓婁紶娴嬭瘯', 'utf8');
  const up = await fetch(
    `http://${HOST}:${PORT}/upload?path=${encodeURIComponent(path.join(SCRATCH, 'from-phone.txt'))}&overwrite=1`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/octet-stream' },
      body: upBytes,
    },
  );
  check('HTTP upload succeeds', up.ok, `status ${up.status}`);

  const after = await request('fs.list', { path: SCRATCH }, 'fs.listing');
  check('uploaded file appears in the listing', after.items.some((i) => i.name === 'from-phone.txt'));

  // 5. A real directory the user will actually browse.
  const realPath = os.homedir();
  const real = await request('fs.list', { path: realPath }, 'fs.listing');
  check('lists the real home directory', real.items.length > 0, `${real.items.length} entries`);
  check('home listing is sorted dirs-first', real.items[0]?.isDir === true, real.items[0]?.name);

  // 6. Cleanup through the API, proving delete works on the live agent too.
  await request('fs.delete', { path: path.join(SCRATCH, 'e2e.txt') }, 'action.result');
  await request('fs.delete', { path: path.join(SCRATCH, 'from-phone.txt') }, 'action.result');
  const cleaned = await request('fs.list', { path: SCRATCH }, 'fs.listing');
  check('deletes remove the files', cleaned.items.length === 0, `${cleaned.items.length} left`);
} catch (err) {
  check('e2e flow completed', false, err.message);
}

ws.close();
fs.rmSync(SCRATCH, { recursive: true, force: true });

const failures = results.filter((r) => !r.passed).length;
console.log(`\nP2 live: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
