/**
 * Dump the exact JSON shape of every DSH SDK session event, so the TermDesk
 * chat client can be written against real payloads instead of guesses.
 *
 * Usage: node tools/sdk-dump.js <provider> <model> <cwd> [prompt]
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline';

const DSH_BIN = path.join(
  os.homedir(),
  'AppData/Roaming/io.github.hairyf.deepseek-harness-desktop/dependencies/dsh',
  'node_modules/@deepseek-ai/dsh/lib/bin.js'
);

const provider = process.argv[2] || 'wolfox';
const model = process.argv[3] || 'spe/deepseek-v4.1-flash';
const cwd = process.argv[4] || process.cwd();
const prompt = process.argv[5] || 'Create a file named probe.txt containing hello, then tell me one short sentence about what you did.';

const child = spawn(process.execPath, [DSH_BIN, '--profile', 'sdk'], {
  cwd,
  stdio: ['pipe', 'pipe', 'pipe'],
  env: { ...process.env },
  windowsHide: true
});

let nextId = 1;
const pending = new Map();
/** type -> first raw event sample */
const samples = new Map();
/** type -> count */
const counts = new Map();
const order = [];
let assistantChunkSamples = 0;
let assistantChunks = [];

function request(method, params, timeoutMs = 240000) {
  const id = String(nextId++);
  const frame = { jsonrpc: '2.0', id, method };
  if (params !== undefined) frame.params = params;
  child.stdin.write(JSON.stringify(frame) + '\n');
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer, method });
  });
}

const rl = readline.createInterface({ input: child.stdout });
rl.on('line', (line) => {
  const t = line.trim();
  if (!t || t[0] !== '{') return;
  let frame;
  try { frame = JSON.parse(t); } catch { return; }
  if (frame.id !== undefined && frame.method === undefined) {
    const slot = pending.get(frame.id);
    if (!slot) return;
    pending.delete(frame.id);
    clearTimeout(slot.timer);
    if (frame.error) slot.reject(new Error(`${slot.method} -> ${frame.error.code}: ${frame.error.message}`));
    else slot.resolve(frame.result);
    return;
  }
  if (frame.method === 'session.event') {
    const ev = frame.params && frame.params.event;
    if (!ev) return;
    const type = ev.type || '(untyped)';
    counts.set(type, (counts.get(type) || 0) + 1);
    if (!samples.has(type)) {
      samples.set(type, ev);
      order.push(type);
    }
    if (type === 'assistant/chunk' && assistantChunkSamples < 6) {
      assistantChunkSamples++;
      assistantChunks.push(ev);
    }
  }
});

const stderrLines = [];
readline.createInterface({ input: child.stderr }).on('line', (l) => stderrLines.push(l));

function truncate(value, depth = 0) {
  if (depth > 6) return '…';
  if (typeof value === 'string') return value.length > 400 ? value.slice(0, 400) + `…(+${value.length - 400})` : value;
  if (Array.isArray(value)) {
    if (value.length > 4) return [truncate(value[0], depth + 1), `…(${value.length} items)`];
    return value.map((v) => truncate(v, depth + 1));
  }
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = truncate(v, depth + 1);
    return out;
  }
  return value;
}

(async () => {
  const sessionId = 'sdk-dump-' + Date.now();
  await request('initialize', { cwd, provider, model }, 180000);
  await request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: prompt }] }, 120000);

  // Wait for idle.
  const deadline = Date.now() + 240000;
  await new Promise((resolve) => {
    const poll = () => {
      if (counts.has('turn/end') || Date.now() > deadline) return resolve();
      setTimeout(poll, 250);
    };
    poll();
  });
  await new Promise((r) => setTimeout(r, 2500)); // let trailing events land

  console.log('===== event counts =====');
  for (const [k, v] of [...counts.entries()].sort()) console.log(`${String(v).padStart(6)}  ${k}`);

  console.log('');
  console.log('===== one sample per event type =====');
  for (const type of order) {
    console.log('');
    console.log(`--- ${type} ---`);
    console.log(JSON.stringify(truncate(samples.get(type)), null, 2));
  }

  console.log('');
  console.log('===== first assistant/chunk samples (streaming granularity) =====');
  assistantChunks.forEach((ev, i) => {
    console.log(`[${i}] ${JSON.stringify(truncate(ev))}`);
  });

  try { await request('shutdown', undefined, 30000); } catch { /* ignore */ }
  setTimeout(() => {
    if (child.exitCode === null) child.kill();
    process.exit(0);
  }, 500);
})().catch((err) => {
  console.log('DUMP FAILED: ' + (err && err.message));
  for (const l of stderrLines.slice(-15)) console.log('  [stderr] ' + l);
  child.kill();
  process.exit(1);
});
