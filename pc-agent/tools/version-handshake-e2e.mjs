/**
 * What a real agent answers a real client about protocol versions.
 *
 * `tools/protocol-negotiation-test.js` pins the rules as functions. This one is
 * the other half: it starts an actual agent process on a free port, speaks the
 * real `auth` frame over a real WebSocket, and reads the real answer. The rules
 * being right does not prove they are wired in.
 *
 * Three clients, in the order that matters:
 *
 *   1. one that declares nothing — every app installed before this field existed;
 *   2. one that speaks the current version — the normal case;
 *   3. one from the future — the agent must not refuse the side nobody updates.
 *
 * Free: no kernel, no model, no network beyond loopback.
 *
 *   node tools/version-handshake-e2e.mjs
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';
import { PROTOCOL_VERSION } from '../src/protocol.js';

const here = path.dirname(fileURLToPath(import.meta.url));

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const PORT = 7500 + Math.floor(Math.random() * 200);

/** Start an agent that prints its token, and wait until it is listening. */
function startAgent() {
  const child = spawn(
    process.execPath,
    [path.join(here, '..', 'src', 'server.js'), '--port', String(PORT), '--host', '127.0.0.1'],
    { cwd: path.join(here, '..'), stdio: ['ignore', 'pipe', 'pipe'] },
  );
  return new Promise((resolve, reject) => {
    let out = '';
    const timer = setTimeout(() => reject(new Error(`agent did not start: ${out.slice(-400)}`)), 15000);
    child.stdout.on('data', (chunk) => {
      out += chunk.toString();
      if (out.includes('监听')) {
        clearTimeout(timer);
        resolve(child);
      }
    });
    child.stderr.on('data', (chunk) => { out += chunk.toString(); });
    child.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`agent exited early (${code}): ${out.slice(-400)}`));
    });
  });
}

/** The machine token lives in ~/.termdesk/token; the agent prints a prefix of it. */
async function readToken() {
  return machineTokenOrSkip('version-handshake-e2e');
}

/** One auth round trip; resolves with the first auth.ok / auth.fail frame. */
function authRoundTrip(token, declaredVersion) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
    const done = (value, err) => {
      try { ws.close(); } catch { /* already gone */ }
      if (err) reject(err); else resolve(value);
    };
    const timer = setTimeout(() => done(null, new Error('no answer to auth')), 10000);
    ws.on('open', () => {
      const frame = { type: 'auth', token };
      if (declaredVersion !== undefined) frame.v = declaredVersion;
      ws.send(JSON.stringify(frame));
    });
    ws.on('message', (raw) => {
      const frame = JSON.parse(raw.toString());
      if (frame.type === 'auth.ok' || frame.type === 'auth.fail') {
        clearTimeout(timer);
        done(frame);
      }
    });
    ws.on('error', (err) => { clearTimeout(timer); done(null, err); });
  });
}

const agent = await startAgent();
try {
  const token = await readToken();

  const silent = await authRoundTrip(token, undefined);
  check('a client that declares no version is accepted',
    silent.type === 'auth.ok', `${silent.type} ${silent.reason ?? ''}`);
  check('and the answer states the protocol', silent.protocol === PROTOCOL_VERSION, `protocol=${silent.protocol}`);
  check('and states the minimum it requires, so a phone can name the side to update',
    Number.isInteger(silent.minV), `minV=${silent.minV}`);
  check('and names the agent build', typeof silent.agent === 'string' && silent.agent.includes('termdesk-pc-agent'),
    silent.agent);

  const current = await authRoundTrip(token, PROTOCOL_VERSION);
  check('a client on the current version is accepted', current.type === 'auth.ok', current.type);

  const newer = await authRoundTrip(token, PROTOCOL_VERSION + 1);
  check('a client NEWER than the agent is accepted (the agent is the side nobody updates)',
    newer.type === 'auth.ok', `${newer.type} ${newer.reason ?? ''}`);

  // A client below the floor is refused. The floor is 1 today, and a client
  // cannot declare 0 (the parser reads that as "declared nothing", which is the
  // compatible case), so this path is exercised by raising the agent's own floor
  // in tools/protocol-negotiation-test.js instead — stated here so the gap is
  // visible rather than assumed covered.
} finally {
  agent.kill();
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
