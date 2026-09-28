/**
 * Probe: can TermDesk drive one DSH SDK runtime over stdio JSON-RPC?
 *
 * Speaks the documented wire contract (@deepseek-ai/dsh-sdk-protocol):
 *   client -> server : initialize | session/prompt | shutdown
 *   server -> client : session.event | session.status | subagent.started | subagent.finished
 *
 * One JSON-RPC 2.0 message per newline-terminated line. Stdout carries protocol
 * frames only; stderr is free-form (the harness prints its ctrl-immune banner
 * there).
 *
 * Usage:
 *   node tools/sdk-probe.js <provider> <model> <cwd> [prompt] [secondPrompt]
 *
 * Exit 0 only when the handshake, the prompt receipt, at least one assistant
 * text event, and every requested follow-up turn all succeeded.
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
const prompt1 = process.argv[5] || 'Reply with exactly the word: PROBE-OK';
const prompt2 = process.argv[6] || 'What word did I ask you to reply with? Answer with just that word.';

let failures = 0;
let passes = 0;
function check(name, ok, detail) {
  if (ok) {
    passes++;
    console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`);
  } else {
    failures++;
    console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`);
  }
}

const child = spawn(process.execPath, [DSH_BIN, '--profile', 'sdk'], {
  cwd,
  stdio: ['pipe', 'pipe', 'pipe'],
  env: { ...process.env },
  windowsHide: true
});

let nextId = 1;
const pending = new Map();
const notifications = [];
const stderrLines = [];

function send(method, params) {
  const id = method === 'shutdown' ? 'shutdown-1' : String(nextId++);
  const frame = { jsonrpc: '2.0', id, method };
  if (params !== undefined) frame.params = params;
  child.stdin.write(JSON.stringify(frame) + '\n');
  return id;
}

function request(method, params, timeoutMs = 240000) {
  const id = send(method, params);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer, method });
  });
}

const rl = readline.createInterface({ input: child.stdout });
rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let frame;
  try {
    frame = JSON.parse(trimmed);
  } catch {
    // Not a protocol frame; stdout should never carry this, so surface it.
    console.log(`  [stdout-not-json] ${trimmed.slice(0, 200)}`);
    return;
  }
  if (frame.id !== undefined && frame.method === undefined) {
    const slot = pending.get(frame.id);
    if (!slot) return;
    pending.delete(frame.id);
    clearTimeout(slot.timer);
    if (frame.error) slot.reject(new Error(`${slot.method} -> ${frame.error.code}: ${frame.error.message}`));
    else slot.resolve(frame.result);
    return;
  }
  if (frame.method !== undefined && frame.id === undefined) {
    notifications.push({ method: frame.method, params: frame.params });
    if (process.env.SDK_PROBE_VERBOSE) {
      console.log(`  [notify] ${frame.method} ${JSON.stringify(frame.params).slice(0, 160)}`);
    }
    return;
  }
});

const rlErr = readline.createInterface({ input: child.stderr });
rlErr.on('line', (line) => {
  stderrLines.push(line);
  if (process.env.SDK_PROBE_VERBOSE) console.log(`  [stderr] ${line}`);
});

/** Collect assistant text from the session event vocabulary. */
function assistantTexts() {
  const out = [];
  for (const n of notifications) {
    if (n.method !== 'session.event') continue;
    const ev = n.params && n.params.event;
    if (!ev) continue;
    // Flatten every content-bearing shape we know the runtime emits.
    const blocks =
      (ev.data && Array.isArray(ev.data.content) && ev.data.content) ||
      (ev.data && ev.data.message && Array.isArray(ev.data.message.content) && ev.data.message.content) ||
      (Array.isArray(ev.content) && ev.content) ||
      null;
    if (!blocks) continue;
    for (const b of blocks) {
      if (b && b.type === 'text' && typeof b.text === 'string') {
        out.push({ type: ev.type, text: b.text });
      }
    }
  }
  return out;
}

function eventTypeCounts() {
  const counts = {};
  for (const n of notifications) {
    if (n.method === 'session.event' && n.params && n.params.event) {
      const t = n.params.event.type;
      counts[t] = (counts[t] || 0) + 1;
    } else if (n.method !== 'session.event') {
      counts['@' + n.method] = (counts['@' + n.method] || 0) + 1;
    }
  }
  return counts;
}

/** Wait until the runtime reports the agent idle again, or the deadline passes. */
function waitForIdle(sinceIndex, timeoutMs = 240000) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const poll = () => {
      for (let i = sinceIndex; i < notifications.length; i++) {
        const n = notifications[i];
        if (n.method === 'session.status' && n.params && n.params.status === 'idle') {
          resolve({ idle: true });
          return;
        }
      }
      if (Date.now() > deadline) {
        resolve({ idle: false });
        return;
      }
      setTimeout(poll, 200);
    };
    poll();
  });
}

(async () => {
  const sessionId = 'sdk-probe-' + Date.now();
  console.log(`provider=${provider} model=${model}`);
  console.log(`cwd=${cwd}`);
  console.log(`sessionId=${sessionId}`);
  console.log('');

  // 1. handshake
  let init;
  try {
    init = await request('initialize', { cwd, provider, model }, 180000);
  } catch (err) {
    check('initialize handshake', false, err.message);
    finish();
    return;
  }
  check('initialize handshake', true, JSON.stringify(init));
  check(
    'serverInfo.name is the wire-stable id',
    init && init.serverInfo && init.serverInfo.name === 'deepseek-harness-sdk-runtime',
    init && init.serverInfo && init.serverInfo.name
  );

  // 2. first prompt
  const before1 = notifications.length;
  let receipt1;
  try {
    receipt1 = await request('session/prompt', {
      sessionId,
      contentBlocks: [{ type: 'text', text: prompt1 }]
    }, 120000);
  } catch (err) {
    check('session/prompt accepted (turn 1)', false, err.message);
    finish();
    return;
  }
  check('session/prompt accepted (turn 1)', !!(receipt1 && receipt1.messageId), JSON.stringify(receipt1));

  const idle1 = await waitForIdle(before1, 240000);
  check('turn 1 reached idle', idle1.idle);
  const texts1 = assistantTexts();
  check('turn 1 produced assistant text', texts1.length > 0, `${texts1.length} text block(s)`);
  const answer1 = texts1.map((t) => t.text).join('').trim();
  check('turn 1 answer matches the request', /PROBE-OK/i.test(answer1), JSON.stringify(answer1.slice(0, 200)));

  // 3. second prompt on the SAME session id — this is the continuity question.
  const before2 = notifications.length;
  let receipt2;
  try {
    receipt2 = await request('session/prompt', {
      sessionId,
      contentBlocks: [{ type: 'text', text: prompt2 }]
    }, 120000);
  } catch (err) {
    check('session/prompt accepted (turn 2)', false, err.message);
    finish();
    return;
  }
  check('session/prompt accepted (turn 2)', !!(receipt2 && receipt2.messageId), JSON.stringify(receipt2));

  const idle2 = await waitForIdle(before2, 240000);
  check('turn 2 reached idle', idle2.idle);
  const texts2 = assistantTexts().slice(texts1.length);
  check('turn 2 produced assistant text', texts2.length > 0, `${texts2.length} text block(s)`);
  const answer2 = texts2.map((t) => t.text).join('').trim();
  check('turn 2 remembered turn 1 (session continuity)', /PROBE-OK/i.test(answer2), JSON.stringify(answer2.slice(0, 200)));

  console.log('');
  console.log('event vocabulary seen:');
  for (const [k, v] of Object.entries(eventTypeCounts()).sort()) console.log(`  ${String(v).padStart(6)}  ${k}`);

  try {
    await request('shutdown', undefined, 60000);
    check('shutdown acknowledged', true);
  } catch (err) {
    check('shutdown acknowledged', false, err.message);
  }

  finish();
})().catch((err) => {
  check('probe completed without an unexpected throw', false, err && err.stack ? err.stack.split('\n')[0] : String(err));
  finish();
});

function finish() {
  const code = child.exitCode;
  setTimeout(() => {
    if (child.exitCode === null) child.kill();
    if (stderrLines.length && (failures || process.env.SDK_PROBE_VERBOSE)) {
      console.log('');
      console.log('stderr tail:');
      for (const l of stderrLines.slice(-12)) console.log('  ' + l);
    }
    console.log('');
    console.log(`${passes} passed, ${failures} failed  (child exit ${code})`);
    process.exit(failures === 0 ? 0 : 1);
  }, 400);
}
