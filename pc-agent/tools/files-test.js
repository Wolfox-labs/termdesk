/**
 * P2 filesystem checks: listing, read, write, create, rename, delete, and —
 * most importantly — that path traversal is actually refused.
 *
 *   node tools/files-test.js
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = Number(process.env.TERMDESK_TEST_PORT || 7442);
const token = machineTokenOrSkip('files-test');

// Confine the test to a scratch directory so nothing real is touched.
// Keep it inside the repo work tree (`.tmp/`, gitignored) rather than the OS
// temp dir, so a test run never writes outside the project folder.
const SANDBOX = path.join(__dirname, '..', '..', '.tmp', `termdesk-test-${Date.now()}`);
fs.mkdirSync(SANDBOX, { recursive: true });

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const agent = spawn(process.execPath, [AGENT, '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  env: { ...process.env, TERMDESK_ROOTS: SANDBOX },
  stdio: ['ignore', 'pipe', 'pipe'],
});
agent.stderr.on('data', (d) => process.stderr.write(`[agent] ${d}`));
await new Promise((r) => setTimeout(r, 2500));

const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
const pending = new Map();
let seq = 0;

ws.on('message', (raw) => {
  const frame = JSON.parse(raw.toString());
  if (frame.type === 'auth.ok') return;
  const waiter = pending.get(frame.type);
  if (waiter) {
    pending.delete(frame.type);
    waiter(frame);
  }
});

function request(type, payload = {}, expect = null) {
  return new Promise((resolve, reject) => {
    const responseType = expect ?? type;
    const timer = setTimeout(() => {
      pending.delete(responseType);
      reject(new Error(`timeout waiting for ${responseType}`));
    }, 25000);
    pending.set(responseType, (frame) => {
      clearTimeout(timer);
      resolve(frame);
    });
    ws.send(JSON.stringify({ type, ...payload }));
  });
}

try {
  await new Promise((resolve) => ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'auth', token }));
    setTimeout(resolve, 700);
  }));

  // --- roots ---------------------------------------------------------------
  const roots = await request('fs.roots');
  check('fs.roots returns the configured root', roots.roots?.includes(SANDBOX), JSON.stringify(roots.roots));

  // --- create + list -------------------------------------------------------
  const mkdirRes = await request('fs.mkdir', { path: SANDBOX, name: 'subdir', kind: 'dir' }, 'action.result');
  check('creates a directory', mkdirRes.ok === true, mkdirRes.message);

  const mkfileRes = await request('fs.mkdir', { path: SANDBOX, name: 'note.txt', kind: 'file' }, 'action.result');
  check('creates a file', mkfileRes.ok === true, mkfileRes.message);

  const listing = await request('fs.list', { path: SANDBOX }, 'fs.listing');
  const names = listing.items.map((i) => i.name);
  check('listing includes created entries', names.includes('subdir') && names.includes('note.txt'), names.join(','));
  check('directories sort first', listing.items[0]?.isDir === true, listing.items[0]?.name);

  // --- write + read --------------------------------------------------------
  const text = '第一行\nsecond line\n';
  const target = path.join(SANDBOX, 'note.txt');
  const writeRes = await request('fs.write', { path: target, text }, 'action.result');
  check('writes text', writeRes.ok === true, writeRes.message);

  const file = await request('fs.read', { path: target }, 'fs.file');
  check('reads text back byte-for-byte', file.text === text, JSON.stringify(file.text));

  // --- rename --------------------------------------------------------------
  const renamed = await request('fs.rename', { path: target, name: 'renamed.txt' }, 'action.result');
  check('renames a file', renamed.ok === true, renamed.message);
  check('renamed file exists on disk', fs.existsSync(path.join(SANDBOX, 'renamed.txt')));

  // --- path traversal must be refused --------------------------------------
  const escapes = [
    path.join(SANDBOX, '..', '..', 'Windows', 'System32', 'drivers', 'etc', 'hosts'),
    'C:\\Windows\\System32\\drivers\\etc\\hosts',
    '..\\..\\..\\Windows\\win.ini',
    path.join(SANDBOX, '..', 'escape.txt'),
  ];
  for (const evil of escapes) {
    const res = await request('fs.read', { path: evil }, 'error');
    check(`refuses to read outside roots: ${path.basename(evil)}`, res.code === 'outside_roots', res.message);
  }

  // Writing outside the roots must also fail.
  const evilWrite = await request('fs.write', { path: 'C:\\Windows\\Temp\\termdesk-evil.txt', text: 'x' }, 'action.result');
  check('refuses to write outside roots', evilWrite.ok === false, evilWrite.message);
  check('nothing was written outside the sandbox', !fs.existsSync('C:\\Windows\\Temp\\termdesk-evil.txt'));

  // --- non-empty directory delete must be refused ---------------------------
  const nested = path.join(SANDBOX, 'subdir', 'inner.txt');
  await request('fs.mkdir', { path: path.join(SANDBOX, 'subdir'), name: 'inner.txt', kind: 'file' }, 'action.result');
  const delNonEmpty = await request('fs.delete', { path: path.join(SANDBOX, 'subdir') }, 'action.result');
  check('refuses to delete a non-empty directory', delNonEmpty.ok === false && delNonEmpty.code === 'not_empty', delNonEmpty.message);

  // --- delete works --------------------------------------------------------
  const delFile = await request('fs.delete', { path: path.join(SANDBOX, 'renamed.txt') }, 'action.result');
  check('deletes a file', delFile.ok === true, delFile.message);
  check('deleted file is gone', !fs.existsSync(path.join(SANDBOX, 'renamed.txt')));

  // --- binary rejection ----------------------------------------------------
  const binPath = path.join(SANDBOX, 'blob.bin');
  fs.writeFileSync(binPath, Buffer.from([0x00, 0x01, 0x02, 0xff]));
  const binRes = await request('fs.read', { path: binPath }, 'error');
  check('refuses to read binary as text', binRes.code === 'binary', binRes.message);

  // --- HTTP transfer: upload + download round trip --------------------------
  const uploadBody = Buffer.from('transfer round trip 上传下载测试', 'utf8');
  const upUrl = `http://127.0.0.1:${PORT}/upload?path=${encodeURIComponent(path.join(SANDBOX, 'up.txt'))}&overwrite=1`;
  const upRes = await fetch(upUrl, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/octet-stream' },
    body: uploadBody,
  });
  check('HTTP upload succeeds', upRes.ok, `status ${upRes.status}`);

  const downRes = await fetch(
    `http://127.0.0.1:${PORT}/download?path=${encodeURIComponent(path.join(SANDBOX, 'up.txt'))}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  const downBody = Buffer.from(await downRes.arrayBuffer());
  check('HTTP download matches upload', downBody.equals(uploadBody), `${downBody.length} bytes`);

  // --- HTTP transfer must reject a bad token -------------------------------
  const badDown = await fetch(
    `http://127.0.0.1:${PORT}/download?path=${encodeURIComponent(path.join(SANDBOX, 'up.txt'))}`,
    { headers: { authorization: 'Bearer wrong-token' } },
  );
  check('HTTP download rejects a bad token', badDown.status === 401, `status ${badDown.status}`);

  const noToken = await fetch(
    `http://127.0.0.1:${PORT}/download?path=${encodeURIComponent(path.join(SANDBOX, 'up.txt'))}`,
  );
  check('HTTP download rejects a missing token', noToken.status === 401, `status ${noToken.status}`);

  // Regression: an upload used to resolve before the write stream flushed,
  // so stat() raced the disk and returned ENOENT as a 500. Repeat it to make
  // the timing window obvious if it ever comes back.
  let uploadRaceFailures = 0;
  for (let i = 0; i < 5; i += 1) {
    const p = path.join(SANDBOX, `race-${i}.txt`);
    const r = await fetch(
      `http://127.0.0.1:${PORT}/upload?path=${encodeURIComponent(p)}&overwrite=1`,
      {
        method: 'POST',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/octet-stream' },
        body: Buffer.from(`race payload ${i}`, 'utf8'),
      },
    );
    if (!r.ok) uploadRaceFailures += 1;
    else if (!fs.existsSync(p)) uploadRaceFailures += 1;
  }
  check('repeated uploads never race the flush', uploadRaceFailures === 0, `${uploadRaceFailures}/5 failed`);

  // A larger body exercises real backpressure through the pipeline.
  const big = Buffer.alloc(5 * 1024 * 1024, 0x41);
  const bigPath = path.join(SANDBOX, 'big.bin');
  const bigRes = await fetch(
    `http://127.0.0.1:${PORT}/upload?path=${encodeURIComponent(bigPath)}&overwrite=1`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/octet-stream' },
      body: big,
    },
  );
  check('uploads a 5 MB body intact', bigRes.ok && fs.statSync(bigPath).size === big.length,
    `status ${bigRes.status}, size ${fs.existsSync(bigPath) ? fs.statSync(bigPath).size : 'missing'}`);

  // --- HTTP download must refuse path traversal ----------------------------
  const evilDown = await fetch(
    `http://127.0.0.1:${PORT}/download?path=${encodeURIComponent('C:\\Windows\\win.ini')}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  check('HTTP download refuses traversal', evilDown.status === 403, `status ${evilDown.status}`);
} catch (err) {
  check('test harness completed', false, err.message);
}

ws.close();
agent.kill('SIGKILL');
fs.rmSync(SANDBOX, { recursive: true, force: true });

const failures = results.filter((r) => !r.passed).length;
console.log(`\nP2: ${results.length - failures}/${results.length} passed`);
// Let the child's handles finish tearing down before exiting, otherwise libuv
// aborts on a closing async handle and pollutes the exit path.
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
