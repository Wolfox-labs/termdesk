/**
 * Negative auth checks. A bridge that lets anything in is worse than no bridge,
 * so prove the agent actually rejects bad clients.
 *
 *   node tools/auth-negative.js
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WebSocket } from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const realToken = machineTokenOrSkip('auth-negative');

const results = [];

/**
 * Connect and send one frame, then assert whether the server let us in or shut
 * us out. `expectAccepted` false means we expect a close without auth.ok.
 */
function attempt(name, url, firstFrame, expectAccepted) {
  return new Promise((resolve) => {
    const ws = new WebSocket(url);
    let gotAuthOk = false;
    let gotAuthFail = null;
    let settled = false;

    const finish = (passed, detail) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      results.push({ name, passed, detail });
      try { ws.close(); } catch { /* already closing */ }
      resolve();
    };

    const timer = setTimeout(() => {
      finish(false, 'timed out waiting for a verdict');
    }, 9000);

    ws.on('open', () => ws.send(JSON.stringify(firstFrame)));

    ws.on('message', (raw) => {
      const f = JSON.parse(raw.toString());
      if (f.type === 'auth.ok') {
        gotAuthOk = true;
        // A successful auth deliberately keeps the socket open, so decide here
        // rather than waiting for a close that will never arrive.
        if (expectAccepted) finish(true, `accepted as ${f.hostname}`);
      }
      if (f.type === 'auth.fail') gotAuthFail = f.reason;
    });

    ws.on('close', (code) => {
      if (expectAccepted) {
        finish(gotAuthOk, gotAuthOk ? 'accepted as expected' : `rejected (code ${code})`);
      } else {
        finish(
          !gotAuthOk && code === 4401,
          `code=${code} authFail=${gotAuthFail ?? 'none'}`,
        );
      }
    });

    ws.on('error', (err) => finish(false, `socket error: ${err.message}`));
  });
}

const url = `ws://${HOST}:${PORT}`;

await attempt('wrong token', url, { type: 'auth', token: 'not-the-real-token' }, false);
await attempt('missing token', url, { type: 'auth' }, false);
await attempt('skip auth, ask status', url, { type: 'status.get' }, false);
await attempt('garbage frame', url, { foo: 'bar' }, false);
await attempt('non-JSON text', url, 'definitely not json', false);
await attempt('correct token', url, { type: 'auth', token: realToken }, true);

console.log('\nAuth checks');
console.log('------------');
let failures = 0;
for (const r of results) {
  console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.name.padEnd(24)} ${r.detail ?? ''}`);
  if (!r.passed) failures += 1;
}
console.log(failures === 0 ? '\nAuth: ALL PASS' : `\nAuth: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
