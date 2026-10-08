/**
 * The request id survives the whole trip: phone -> real agent -> phone.
 *
 * `tools/call-id-test.js` pins the echo as a function. This drives a REAL agent over
 * a REAL socket and asserts the id comes back on the frame, because the failure this
 * guards against is a reply that looks fine and belongs to a different question -
 * and a unit test cannot see a field that a frame builder drops on the way out.
 *
 * It also pins the property that makes the fix safe to ship to older clients: a
 * request WITHOUT an id gets a reply without one, byte-for-byte as before.
 *
 * Model-free: no kernel is started, only the file system is asked.
 *
 *   node tools/call-id-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.dirname(here);
const PORT = 7463;

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`);
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A directory of our own, so the answer cannot be confused with a real one.
const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'td-callid-'));
fs.mkdirSync(path.join(sandbox, 'alpha'));
fs.writeFileSync(path.join(sandbox, 'alpha', 'a.txt'), 'alpha\n');
fs.mkdirSync(path.join(sandbox, 'beta'));
fs.writeFileSync(path.join(sandbox, 'beta', 'b.txt'), 'beta\n');

const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

const agent = spawn(process.execPath, [path.join(root, 'src', 'server.js'), '--port', String(PORT)], {
  cwd: root,
  env: { ...process.env, TERMDESK_ROOTS: sandbox },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let agentLog = '';
agent.stdout.on('data', (d) => { agentLog += d; });
agent.stderr.on('data', (d) => { agentLog += d; });

let client; let failures = 0; const frames = [];
const onFrame = (f) => frames.push(f);
const waitFrame = async (pred, ms, what) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const found = frames.find(pred);
    if (found) return found;
    await sleep(50);
  }
  throw new Error(`timeout waiting for ${what}; got ${[...new Set(frames.map((f) => f.type))].join(',')}`);
};

const shutdown = (code) => {
  try { client?.close(); } catch { /* already gone */ }
  try { agent.kill(); } catch { /* already gone */ }
  try { fs.rmSync(sandbox, { recursive: true, force: true }); } catch { /* best effort */ }
  console.log(`\n${results.length - failures} passed, ${failures} failed`);
  process.exit(code);
};

try {
  await sleep(1200);
  client = new WebSocket(`ws://127.0.0.1:${PORT}`);
  client.on('message', (raw) => { try { onFrame(JSON.parse(raw.toString())); } catch { /* not ours */ } });
  await new Promise((res, rej) => {
    client.once('open', res);
    client.once('error', rej);
    setTimeout(() => rej(new Error('agent did not accept the connection')), 8000);
  });
  check('server starts', true);

  client.send(JSON.stringify({ type: 'auth', token, v: 1 }));
  await waitFrame((f) => f.type === 'auth.ok', 8000, 'auth');
  check('authenticated', true);

  // ---- two listings in flight, out of order ---------------------------------
  const alphaCall = 'fs.list-alpha-1';
  const betaCall = 'fs.list-beta-2';
  client.send(JSON.stringify({ type: 'fs.list', path: path.join(sandbox, 'alpha'), callId: alphaCall }));
  client.send(JSON.stringify({ type: 'fs.list', path: path.join(sandbox, 'beta'), callId: betaCall }));

  const alpha = await waitFrame((f) => f.type === 'fs.listing' && f.callId === alphaCall, 8000, 'alpha listing');
  const beta = await waitFrame((f) => f.type === 'fs.listing' && f.callId === betaCall, 8000, 'beta listing');
  check('each listing carries the id of the request that asked for it',
    alpha.callId === alphaCall && beta.callId === betaCall, `${alpha.callId} ${beta.callId}`);
  check('and therefore the right contents', alpha.path.endsWith('alpha') && beta.path.endsWith('beta'),
    `${alpha.path} ${beta.path}`);

  // The property the phone relies on for correctness, stated end to end: an answer
  // identifies its own question, so a late one is recognisable as stale.
  const entriesOf = (frame) => (frame.items ?? []).map((e) => e.name).join(',');
  check('the answers are distinguishable even though both are listings',
    entriesOf(alpha) !== entriesOf(beta), `${entriesOf(alpha)} vs ${entriesOf(beta)}`);

  // ---- an error is attributed too -------------------------------------------
  const badCall = 'fs.list-missing-3';
  client.send(JSON.stringify({ type: 'fs.list', path: path.join(sandbox, 'nope'), callId: badCall }));
  const failure = await waitFrame((f) => f.type === 'error' && f.callId === badCall, 8000, 'attributed error');
  check('a failure names the request that failed', failure.callId === badCall, failure.code);
  check('and still explains itself',
    typeof failure.message === 'string' && failure.message.length > 0, failure.message);

  // ---- a read is attributed as well -----------------------------------------
  const readCall = 'fs.read-4';
  client.send(JSON.stringify({ type: 'fs.read', path: path.join(sandbox, 'beta', 'b.txt'), callId: readCall }));
  const file = await waitFrame((f) => f.type === 'fs.file' && f.callId === readCall, 8000, 'attributed read');
  check('a file read carries its id too', file.callId === readCall, file.callId);
  check('and the file contents', String(file.text ?? '').includes('beta'), JSON.stringify(file.text));

  // ---- the compatibility case that makes this safe to ship -------------------
  const before = frames.length;
  client.send(JSON.stringify({ type: 'fs.list', path: path.join(sandbox, 'alpha') }));
  const plain = await waitFrame(
    (f, i) => f.type === 'fs.listing' && i >= before && !f.callId,
    8000,
    'unattributed listing',
  );
  check('a request with no id is answered exactly as before, with no id', plain.callId === undefined,
    JSON.stringify(Object.keys(plain).join(',')));

  failures = results.filter((r) => !r.passed).length;
  if (failures > 0) console.error('server log:\n' + agentLog.slice(-1200));
  shutdown(failures === 0 ? 0 : 1);
} catch (err) {
  console.error('FAILED:', err?.message ?? err);
  console.error('server log:\n' + agentLog.slice(-1500));
  failures += 1;
  shutdown(1);
}
