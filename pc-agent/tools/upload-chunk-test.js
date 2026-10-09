/**
 * P5-4 chunked-upload checks.
 *
 * Spawns its own agent confined to a sandbox, opens a session, PUTs chunks out
 * of order (to prove offset writes), commits, and verifies the assembled bytes.
 * Also exercises abort, missing-chunk refusal, auth and path-escape rejection.
 *
 *   node tools/upload-chunk-test.js
 *
 * Scratch and the agent's file roots live in the repo `.tmp/` so a run never
 * writes outside the project folder.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = Number(process.env.TERMDESK_TEST_PORT || 7443);

const token = machineTokenOrSkip('upload-chunk-test');
const base = `http://127.0.0.1:${PORT}`;
const auth = { authorization: `Bearer ${token}` };

const SANDBOX = path.join(__dirname, '..', '..', '.tmp', `termdesk-chunk-${Date.now()}`);
fs.mkdirSync(SANDBOX, { recursive: true });

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** A deterministic body so we can compare after reassembly. */
function makeBody(size) {
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i += 1) buf[i] = (i * 31 + 7) & 0xff;
  return buf;
}

async function json(res) {
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { raw: text }; }
}

const agent = spawn(process.execPath, [AGENT, '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  env: { ...process.env, TERMDESK_ROOTS: SANDBOX },
  stdio: ['ignore', 'pipe', 'pipe'],
});
agent.stderr.on('data', (d) => process.stderr.write(`[agent] ${d}`));
await new Promise((r) => setTimeout(r, 2500));

const target = path.join(SANDBOX, 'chunked.bin');
const SIZE = 10 * 1024 * 1024 + 12345; // not a multiple of chunkSize
const CHUNK = 2 * 1024 * 1024;
const body = makeBody(SIZE);

let uploadId = null;

try {
  // 1. open session
  const openRes = await fetch(
    `${base}/upload/session?path=${encodeURIComponent(target)}&size=${SIZE}&chunkSize=${CHUNK}&overwrite=1`,
    { method: 'POST', headers: auth, body: new Uint8Array(0) },
  );
  const opened = await json(openRes);
  check('opens a chunked session', openRes.ok && opened.ok === true,
    `HTTP ${openRes.status} id=${opened.uploadId ?? 'none'}`);
  uploadId = opened.uploadId;
  check('reports chunk plan', opened.totalChunks === Math.ceil(SIZE / CHUNK) && opened.chunkSize === CHUNK,
    `chunks=${opened.totalChunks} chunkSize=${opened.chunkSize}`);

  // 2. upload chunks OUT OF ORDER — offset writes must not corrupt the file.
  const order = [];
  for (let i = 0; i < opened.totalChunks; i += 1) order.push(i);
  order.reverse(); // last chunk first: exercises the tail-size path early
  let chunksOk = true;
  for (const index of order) {
    const start = index * CHUNK;
    const slice = body.subarray(start, Math.min(start + CHUNK, SIZE));
    const put = await fetch(`${base}/upload/session?uploadId=${uploadId}&index=${index}`, {
      method: 'PUT',
      headers: { ...auth, 'content-type': 'application/octet-stream' },
      body: new Uint8Array(slice),
    });
    const info = await json(put);
    if (!put.ok || !info.ok) {
      chunksOk = false;
      check(`chunk ${index} accepted`, false, `HTTP ${put.status} ${info.message ?? info.raw ?? ''}`);
    }
  }
  check('all chunks accepted out of order', chunksOk, `${order.length} chunks`);

  // 3. commit
  const commit = await fetch(`${base}/upload/session/commit?uploadId=${uploadId}`, {
    method: 'POST',
    headers: auth,
    body: new Uint8Array(0),
  });
  const done = await json(commit);
  check('commit succeeds', commit.ok && done.ok === true, `HTTP ${commit.status} size=${done.sizeBytes}`);

  // 4. bytes match
  const written = fs.readFileSync(target);
  check('assembled bytes match source', written.length === SIZE && written.equals(body),
    `len=${written.length}/${SIZE}`);

  // 5. missing-chunk commit is refused
  const target2 = path.join(SANDBOX, 'incomplete.bin');
  const open2 = await fetch(
    `${base}/upload/session?path=${encodeURIComponent(target2)}&size=${CHUNK}&chunkSize=${CHUNK}&overwrite=1`,
    { method: 'POST', headers: auth, body: new Uint8Array(0) },
  );
  const opened2 = await json(open2);
  const commit2 = await fetch(`${base}/upload/session/commit?uploadId=${opened2.uploadId}`, {
    method: 'POST',
    headers: auth,
    body: new Uint8Array(0),
  });
  const info2 = await json(commit2);
  check('refuses commit with missing chunks', commit2.status === 409 && info2.code === 'incomplete',
    `HTTP ${commit2.status} ${info2.code}`);

  // 6. abort cleans up
  const abort = await fetch(`${base}/upload/session?uploadId=${opened2.uploadId}`, {
    method: 'DELETE',
    headers: auth,
  });
  const aborted = await json(abort);
  check('abort drops the session', abort.ok && aborted.ok === true, aborted.code ?? '');
  const commit3 = await fetch(`${base}/upload/session/commit?uploadId=${opened2.uploadId}`, {
    method: 'POST',
    headers: auth,
    body: new Uint8Array(0),
  });
  check('commit after abort is 404', commit3.status === 404, `HTTP ${commit3.status}`);

  // 7. bad token is refused
  const noAuth = await fetch(
    `${base}/upload/session?path=${encodeURIComponent(target)}&size=1&overwrite=1`,
    { method: 'POST', headers: { authorization: 'Bearer wrong' }, body: new Uint8Array(0) },
  );
  check('session open rejects bad token', noAuth.status === 401, `HTTP ${noAuth.status}`);

  // 8. path outside roots is refused
  const evil = await fetch(
    `${base}/upload/session?path=${encodeURIComponent('C:\\Windows\\evil.bin')}&size=10&overwrite=1`,
    { method: 'POST', headers: auth, body: new Uint8Array(0) },
  );
  check('session open rejects path outside roots', evil.status === 403, `HTTP ${evil.status}`);

  // 9. GET status lists received chunks (resume checkpoint)
  const target3 = path.join(SANDBOX, 'resume.bin');
  const open3 = await fetch(
    `${base}/upload/session?path=${encodeURIComponent(target3)}&size=${SIZE}&chunkSize=${CHUNK}&overwrite=1`,
    { method: 'POST', headers: auth, body: new Uint8Array(0) },
  );
  const opened3 = await json(open3);
  const slice0 = body.subarray(0, CHUNK);
  await fetch(`${base}/upload/session?uploadId=${opened3.uploadId}&index=0`, {
    method: 'PUT',
    headers: { ...auth, 'content-type': 'application/octet-stream' },
    body: new Uint8Array(slice0),
  });
  const statusRes = await fetch(`${base}/upload/session?uploadId=${opened3.uploadId}`, { headers: auth });
  const status = await json(statusRes);
  check('status reports received chunks', statusRes.ok && Array.isArray(status.receivedChunks) && status.receivedChunks.includes(0),
    `received=${JSON.stringify(status.receivedChunks)}`);
} catch (err) {
  check('harness completed', false, err.message);
}

agent.kill('SIGKILL');
fs.rmSync(SANDBOX, { recursive: true, force: true });

const failures = results.filter((r) => !r.passed).length;
console.log(`\nchunked upload: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
