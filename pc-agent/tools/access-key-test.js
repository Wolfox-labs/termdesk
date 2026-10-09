/**
 * P5-3 access-key (second factor) checks.
 *
 * Spawns an agent with TERMDESK_ACCESS_KEY set and proves the key is required
 * on /healthz, on the transfer endpoints, and on the WebSocket upgrade — and
 * that the pairing token alone is no longer enough.
 *
 *   node tools/access-key-test.js
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = Number(process.env.TERMDESK_TEST_PORT || 7444);
const ACCESS = 'test-access-key-9f3a';

const token = machineTokenOrSkip('access-key-test');
const base = `http://127.0.0.1:${PORT}`;

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const agent = spawn(process.execPath, [AGENT, '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  env: { ...process.env, TERMDESK_ACCESS_KEY: ACCESS, TERMDESK_ROOTS: os.homedir() },
  stdio: ['ignore', 'pipe', 'pipe'],
});
agent.stderr.on('data', (d) => process.stderr.write(`[agent] ${d}`));
await new Promise((r) => setTimeout(r, 2500));

try {
  // healthz without key -> 401
  const bare = await fetch(`${base}/healthz`);
  check('healthz rejects missing key', bare.status === 401, `HTTP ${bare.status}`);

  // healthz with wrong key -> 401
  const wrong = await fetch(`${base}/healthz`, { headers: { 'x-termdesk-key': 'nope' } });
  check('healthz rejects wrong key', wrong.status === 401, `HTTP ${wrong.status}`);

  // healthz with correct key -> 200
  const good = await fetch(`${base}/healthz`, { headers: { 'x-termdesk-key': ACCESS } });
  check('healthz accepts correct key', good.status === 200, `HTTP ${good.status}`);

  // query-string form also works
  const q = await fetch(`${base}/healthz?access=${encodeURIComponent(ACCESS)}`);
  check('healthz accepts ?access=', q.status === 200, `HTTP ${q.status}`);

  // transfer endpoint without key -> 401 even with a valid pairing token
  const dl = await fetch(`${base}/download?path=${encodeURIComponent(os.homedir())}`, {
    headers: { authorization: `Bearer ${token}` },
  });
  check('download rejects missing key (token alone insufficient)', dl.status === 401, `HTTP ${dl.status}`);

  // WebSocket upgrade without key is closed
  const wsNoKey = await new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
    let got = null;
    ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
    ws.on('close', (code) => resolve({ code, got }));
    ws.on('error', () => resolve({ code: 'error', got }));
    ws.on('message', (raw) => { try { got = JSON.parse(raw.toString()).type; } catch { /* ignore */ } });
    setTimeout(() => { try { ws.close(); } catch { /* ignore */ } resolve({ code: 'timeout', got }); }, 4000);
  });
  check('ws rejects missing key', wsNoKey.code === 4401 || wsNoKey.got !== 'auth.ok',
    `code=${wsNoKey.code} got=${wsNoKey.got}`);

  // WebSocket with key + token authenticates
  const wsOk = await new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}?access=${encodeURIComponent(ACCESS)}`);
    let got = null;
    ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
    ws.on('message', (raw) => {
      try { got = JSON.parse(raw.toString()).type; } catch { /* ignore */ }
      if (got === 'auth.ok') { ws.close(); resolve({ got }); }
    });
    ws.on('close', () => resolve({ got }));
    ws.on('error', () => resolve({ got: 'error' }));
    setTimeout(() => { try { ws.close(); } catch { /* ignore */ } resolve({ got: got ?? 'timeout' }); }, 4000);
  });
  check('ws accepts key + token', wsOk.got === 'auth.ok', `got=${wsOk.got}`);
} catch (err) {
  check('harness completed', false, err.message);
}

agent.kill('SIGKILL');

const failures = results.filter((r) => !r.passed).length;
console.log(`\naccess key: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
