/**
 * The local kernel's delivery contract, against a real server.
 *
 * The phone will download a ~90 MB payload and install it as its sandbox, so the
 * things it depends on are checked here rather than discovered in the field:
 *
 *   - no token -> 401 (the payload is not public)
 *   - the manifest names a package, its size and its sha256
 *   - a Range request returns exactly those bytes (so a phone on a flaky
 *     connection can resume)
 *   - the bytes served are the bytes of the file the manifest describes
 *
 * Skipped, not failed, on a machine where the payload was never built: the rest
 * of the suite has to stay meaningful there.
 *
 *   node tools/local-kernel-routes-test.mjs
 */
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { localKernelDir, readLocalKernel } from '../src/localkernel.js';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_LOCAL_KERNEL_PORT ?? 7434);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

let passes = 0;
let failures = 0;
const check = (name, ok, detail = '') => {
  if (ok) { passes += 1; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures += 1; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const found = readLocalKernel();
if (!found.ok || !found.present) {
  console.log(`  SKIP  no local-kernel payload on this machine (${localKernelDir()})`);
  console.log(`\n${passes} passed, ${failures} failed`);
  process.exit(0);
}

const server = spawn(process.execPath, ['src/server.js', '--host', '127.0.0.1', '--port', String(PORT)], {
  cwd: ROOT,
  stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
server.stdout.on('data', (d) => { log += d; });
server.stderr.on('data', (d) => { log += d; });

try {
  let up = false;
  for (let i = 0; i < 50 && !up; i += 1) {
    try { up = (await fetch(`${BASE}/healthz`)).ok; } catch { up = false; }
    if (!up) await sleep(200);
  }
  check('server starts', up);

  const anonymous = await fetch(`${BASE}/kernel/local`);
  check('the manifest is not public', anonymous.status === 401, String(anonymous.status));

  const auth = { headers: { Authorization: `Bearer ${TOKEN}` } };
  const manifestRes = await fetch(`${BASE}/kernel/local`, auth);
  const body = await manifestRes.json();
  check('the manifest is served', manifestRes.status === 200 && body.ok === true, String(manifestRes.status));
  const manifest = body.manifest ?? {};
  check('the manifest names the payload', typeof manifest.package === 'string' && manifest.package.length > 0, manifest.package);
  check('the manifest carries a sha256', /^[0-9a-f]{64}$/.test(String(manifest.sha256)), String(manifest.sha256).slice(0, 12));
  check('the manifest reports the real size', body.sizeBytes === fs.statSync(found.packagePath).size, `${body.sizeBytes}`);
  check('the manifest says where the kernel lives on the phone',
    String(manifest.prefix ?? '').includes('dev.termdesk.app'), String(manifest.prefix));

  // A range request must return exactly those bytes, or a resumed download would
  // splice two different offsets together.
  const CHUNK = 65536;
  const ranged = await fetch(`${BASE}/kernel/local.pkg`, {
    headers: { Authorization: `Bearer ${TOKEN}`, Range: `bytes=0-${CHUNK - 1}` },
  });
  const rangedBytes = Buffer.from(await ranged.arrayBuffer());
  check('a range request answers 206', ranged.status === 206, String(ranged.status));
  check('a range request returns the asked-for length', rangedBytes.length === CHUNK, `${rangedBytes.length}`);
  // Read exactly the first CHUNK bytes: `readFileSync(path, { length })` returned
  // the whole file here, which made the comparison fail against a correct server.
  const fd = fs.openSync(found.packagePath, 'r');
  const expected = Buffer.alloc(CHUNK);
  fs.readSync(fd, expected, 0, CHUNK, 0);
  fs.closeSync(fd);
  check('the served bytes are the file bytes', rangedBytes.equals(expected));
  check('the range header reports the whole size',
    String(ranged.headers.get('content-range') ?? '') === `bytes 0-${CHUNK - 1}/${body.sizeBytes}`,
    String(ranged.headers.get('content-range')));

  // And the hash the phone will compute is the hash of what it downloads.
  if (process.env.TERMDESK_LOCAL_KERNEL_FULL_HASH === '1') {
    const whole = Buffer.from(await (await fetch(`${BASE}/kernel/local.pkg`, auth)).arrayBuffer());
    const digest = crypto.createHash('sha256').update(whole).digest('hex');
    check('a full download hashes to the manifest sha256', digest === manifest.sha256, digest.slice(0, 12));
  } else {
    console.log('  (full-download hash check skipped: set TERMDESK_LOCAL_KERNEL_FULL_HASH=1)');
  }
} catch (err) {
  failures += 1;
  console.log(`  FAIL  ${String(err?.message ?? err)}`);
  console.log(log.slice(-500));
} finally {
  // Let the child actually exit before this process does: killing and exiting in
  // the same tick trips a libuv assertion on Windows.
  await new Promise((resolve) => {
    server.once('exit', resolve);
    server.kill();
    setTimeout(resolve, 2000);
  });
}

console.log(`\n${passes} passed, ${failures} failed`);
// No process.exit(): leaving while fetch's sockets are still closing trips a libuv
// assertion on Windows, which turns a green run into a crashed one.
process.exitCode = failures === 0 ? 0 : 1;
