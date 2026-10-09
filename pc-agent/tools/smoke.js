/**
 * P0 end-to-end check for the TermDesk agent.
 *
 * Connects over WebSocket, authenticates with the stored token, subscribes to
 * status, and prints the frames that come back. Run with the agent already up:
 *
 *   node tools/smoke.js
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WebSocket } from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const token = machineTokenOrSkip('smoke');

const url = `ws://${HOST}:${PORT}`;
console.log(`connecting to ${url}`);
const ws = new WebSocket(url);

let statusFrames = 0;
let failures = 0;

const fail = (msg) => {
  failures += 1;
  console.error(`FAIL: ${msg}`);
};

const done = () => {
  ws.close();
  if (failures === 0) {
    console.log('\nP0 smoke: PASS');
    process.exit(0);
  }
  console.log(`\nP0 smoke: FAIL (${failures} problem(s))`);
  process.exit(1);
};

const timeout = setTimeout(() => {
  fail('timed out waiting for 2 status frames');
  done();
}, 20000);

ws.on('open', () => {
  console.log('socket open, sending auth');
  ws.send(JSON.stringify({ type: 'auth', token }));
});

ws.on('message', (raw) => {
  const frame = JSON.parse(raw.toString());
  if (frame.type === 'auth.ok') {
    console.log(`auth.ok  hostname=${frame.hostname} protocol=${frame.protocol}`);
    ws.send(JSON.stringify({ type: 'status.subscribe', intervalMs: 700 }));
  } else if (frame.type === 'status') {
    statusFrames += 1;
    const s = frame.status;
    console.log(
      `status   cpu=${s.cpu.usagePercent}% cores=${s.cpu.cores} ` +
        `mem=${s.memory.usedPercent}% (${(s.memory.usedBytes / 1024 ** 3).toFixed(1)}/${(s.memory.totalBytes / 1024 ** 3).toFixed(1)} GB) ` +
        `disks=${s.disks.map((d) => `${d.root}${d.usedPercent}%`).join(' ')}`,
    );
    if (statusFrames === 2) {
      clearTimeout(timeout);
      const s2 = frame.status;
      if (typeof s2.cpu.usagePercent !== 'number') fail('cpu.usagePercent is not a number on the 2nd frame');
      if (!Array.isArray(s2.disks)) fail('disks is not an array');
      if (s2.disks.length === 0) fail('no disks were reported');
      done();
    }
  } else if (frame.type === 'error' || frame.type === 'auth.fail') {
    fail(`server error frame: ${JSON.stringify(frame)}`);
  }
});

ws.on('error', (err) => {
  fail(`socket error: ${err.message}`);
  done();
});
