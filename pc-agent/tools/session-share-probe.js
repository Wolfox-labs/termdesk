/**
 * Can a second DSH instance resume the conversation that the running desktop
 * app is using?
 *
 * This is the question that decides whether the user can continue THIS
 * conversation from their phone. Reading the source suggested session state is
 * shared on disk and resumable by id, but the same source has a `session/agent-busy`
 * error, which means the answer may be "only if the other instance releases it".
 * Guessing between those two is not acceptable, so this measures it.
 *
 * Runs a headless SDK runtime, attempts `session/prompt` against the given
 * sessionId, and reports exactly what the runtime says.
 *
 * Usage: node tools/session-share-probe.js <sessionId> [cwd]
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';

const sessionId = process.argv[2];
const cwd = process.argv[3] || process.cwd();
if (!sessionId) {
  console.error('usage: node tools/session-share-probe.js <sessionId> [cwd]');
  process.exit(2);
}

const DSH_BIN = path.join(
  os.homedir(),
  'AppData/Roaming/io.github.hairyf.deepseek-harness-desktop/dependencies/dsh',
  'node_modules/@deepseek-ai/dsh/lib/bin.js',
);

console.log(`session id : ${sessionId}`);
console.log(`cwd        : ${cwd}`);
console.log('');

const child = spawn(process.execPath, [DSH_BIN, '--profile', 'sdk'], {
  cwd,
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

let nextId = 1;
const pending = new Map();
const notifications = [];
const stderr = [];

function request(method, params, timeoutMs = 60000) {
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

readline.createInterface({ input: child.stdout }).on('line', (line) => {
  const t = line.trim();
  if (!t || t[0] !== '{') return;
  let f;
  try { f = JSON.parse(t); } catch { return; }
  if (f.id !== undefined && f.method === undefined) {
    const slot = pending.get(f.id);
    if (!slot) return;
    pending.delete(f.id);
    clearTimeout(slot.timer);
    if (f.error) slot.reject(new Error(`RPC ${f.error.code}: ${f.error.message}`));
    else slot.resolve(f.result);
    return;
  }
  if (f.method === 'session.event') {
    const ev = f.params?.event;
    if (ev) notifications.push(ev);
    if (ev?.type === 'turn/end') console.log('[turn/end]');
  }
});
readline.createInterface({ input: child.stderr }).on('line', (l) => {
  if (!l.startsWith('[ctrl-immune]')) stderr.push(l);
});

function eventTypes() {
  const c = {};
  for (const e of notifications) c[e.type] = (c[e.type] || 0) + 1;
  return c;
}

(async () => {
  // 1. handshake
  await request('initialize', { cwd, provider: 'wolfox', model: 'spe/deepseek-v4.1-flash' }, 120000);
  console.log('initialize: OK');

  // 2. THE question — prompt the existing session id
  const before = notifications.length;
  let receipt = null;
  let failure = null;
  try {
    receipt = await request('session/prompt', {
      sessionId,
      contentBlocks: [{ type: 'text', text: 'Reply with exactly: RESUME-OK' }],
    }, 120000);
    console.log(`session/prompt: ACCEPTED  messageId=${receipt?.messageId}`);
  } catch (err) {
    failure = err.message;
    console.log(`session/prompt: REFUSED   ${failure}`);
  }

  if (receipt) {
    // Wait for the turn to end.
    const deadline = Date.now() + 180000;
    while (Date.now() < deadline) {
      if (notifications.slice(before).some((e) => e.type === 'turn/end')) break;
      await new Promise((r) => setTimeout(r, 300));
    }
    const fresh = notifications.slice(before);
    const answer = fresh
      .filter((e) => e.type === 'assistant/message')
      .flatMap((e) => (e.data?.message?.content ?? []).filter((c) => c.type === 'text').map((c) => c.text))
      .join(' ')
      .trim();
    console.log('');
    console.log(`assistant answered   : ${JSON.stringify(answer.slice(0, 200))}`);
    console.log(`events this turn     : ${fresh.length}`);
    // If the runtime resumed the stored conversation, it replays prior history
    // before answering; a brand-new empty session would show none.
    const sawHistory = fresh.some((e) => e.type === 'user/message');
    console.log(`prior history replayed: ${sawHistory}`);
  }

  console.log('');
  console.log('event types seen:', JSON.stringify(eventTypes()));
  if (stderr.length) {
    console.log('');
    console.log('stderr tail:');
    for (const l of stderr.slice(-8)) console.log('  ' + l);
  }

  try { await request('shutdown', undefined, 20000); } catch { /* ignore */ }
  setTimeout(() => {
    if (child.exitCode === null) child.kill();
    process.exit(receipt ? 0 : 1);
  }, 500);
})().catch((err) => {
  console.log(`probe threw: ${err.message}`);
  for (const l of stderr.slice(-8)) console.log('  [stderr] ' + l);
  child.kill();
  process.exit(1);
});
