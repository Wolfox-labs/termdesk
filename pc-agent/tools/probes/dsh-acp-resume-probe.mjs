/**
 * Can the phone take over a conversation the desktop is in the middle of?
 *
 * That is the owner's core requirement, and it turns on one question the code cannot answer
 * by itself: does the kernel that owns those conversations offer a way back into one? TermDesk
 * says "not yet" for the DSH `sdk` profile (`registry.js`: `resume: false`) because that
 * transport has no such call. But DSH also ships an `acp` profile, and ACP has a method for
 * exactly this (`session/load`), so the answer may be "yes, through the other door".
 *
 * This probe asks the runtime itself and prints what it says, so the decision is made on
 * evidence rather than on the shape of a file on disk:
 *
 *   1. `initialize`          — what the runtime declares it can do (`loadSession`?);
 *   2. `session/load`        — can it open a session that already exists on disk, by the id
 *                              the phone learned from the session index;
 *   3. (only if asked) a prompt — NOT sent by default: no model call happens here.
 *
 * Read-only with respect to the session: loading replays it, it does not write a turn. The
 * transcript it returns is not printed in full (it is somebody's conversation); only counts.
 *
 *   node tools/probes/dsh-acp-resume-probe.mjs <sessionId>
 *   node tools/probes/dsh-acp-resume-probe.mjs <sessionId> --cwd E:\aiPic\termdesk
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const DSH_BIN = path.join(
  process.env.APPDATA ?? path.join(os.homedir(), 'AppData', 'Roaming'),
  'io.github.hairyf.deepseek-harness-desktop',
  'dependencies', 'dsh', 'node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js',
);

const args = process.argv.slice(2);
const sessionId = args.find((a) => !a.startsWith('--')) ?? null;
const cwdIndex = args.indexOf('--cwd');
const cwd = cwdIndex >= 0 ? args[cwdIndex + 1] : process.cwd();

if (!fs.existsSync(DSH_BIN)) {
  console.error(`the DSH launcher is not installed at ${DSH_BIN}`);
  process.exit(2);
}

console.log(`dsh:     ${DSH_BIN}`);
console.log(`profile: acp`);
console.log(`session: ${sessionId ?? '(none given — only initialize and session/new will be tried)'}`);
console.log(`cwd:     ${cwd}`);
console.log('');

const child = spawn(process.execPath, [DSH_BIN, '--profile', 'acp'], {
  cwd,
  stdio: ['pipe', 'pipe', 'pipe'],
  env: { ...process.env },
});

let buffer = '';
const pending = new Map();
const notifications = [];
let nextId = 1;

child.stdout.on('data', (chunk) => {
  buffer += chunk.toString();
  let index = buffer.indexOf('\n');
  while (index !== -1) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    index = buffer.indexOf('\n');
    if (!line) continue;
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    if (message.id !== undefined && pending.has(message.id)) {
      const { resolve } = pending.get(message.id);
      pending.delete(message.id);
      resolve(message);
    } else if (message.method) {
      notifications.push(message);
      // The runtime asks the client for things (permissions, terminals, fs). Answering "no"
      // is enough for a probe whose question is about sessions, and it keeps this free.
      if (message.id !== undefined) {
        child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: message.id, result: { outcome: { outcome: 'cancelled' } } })}\n`);
      }
    }
  }
});
child.stderr.on('data', (chunk) => {
  const text = chunk.toString().trim();
  if (text) console.log(`  [runtime] ${text.slice(0, 200)}`);
});

function call(method, params, timeoutMs = 20000) {
  const id = nextId++;
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      resolve({ error: { message: `${method} timed out after ${timeoutMs}ms` } });
    }, timeoutMs);
    pending.set(id, {
      resolve: (message) => { clearTimeout(timer); resolve(message); },
    });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  });
}

const summarise = (result) => {
  if (!result || typeof result !== 'object') return String(result);
  const keys = Object.keys(result);
  const out = {};
  for (const key of keys) {
    const value = result[key];
    if (Array.isArray(value)) out[key] = `[${value.length}]`;
    else if (value && typeof value === 'object') out[key] = `{${Object.keys(value).join(',')}}`;
    else out[key] = value;
  }
  return JSON.stringify(out);
};

try {
  const init = await call('initialize', {
    protocolVersion: 1,
    clientCapabilities: {
      fs: { readTextFile: false, writeTextFile: false },
      terminal: true,
    },
  });
  console.log('initialize ->', init.error ? `ERROR ${init.error.message}` : summarise(init.result));
  const caps = init.result?.agentCapabilities ?? {};
  console.log(`  loadSession: ${caps.loadSession === true ? 'YES' : caps.loadSession === false ? 'no' : '(not declared)'}`);
  console.log(`  sessionCapabilities: ${JSON.stringify(caps.sessionCapabilities ?? null)}`);
  console.log('');

  if (!sessionId) {
    const list = await call('session/list', {}, 20000);
    console.log('session/list ->', list.error ? `ERROR ${list.error.message}` : summarise(list.result));
    const sessions = list.result?.sessions ?? [];
    for (const s of sessions.slice(0, 8)) {
      console.log(`  ${s.sessionId ?? s.id}  ${s.cwd ?? ''}  ${s.title ?? ''}`);
    }
    console.log('');
    console.log('no session id given, stopping here');
  } else {
    // `resume` is what this runtime declares (not `loadSession`): ACP's newer in-place resume,
    // which is exactly what "take over the conversation that is already running" needs.
    const resume = await call('session/resume', { sessionId, cwd, mcpServers: [] }, 30000);
    if (resume.error) {
      console.log(`session/resume -> ERROR ${resume.error.message}`);
    } else {
      const replayed = notifications.filter((n) => n.method === 'session/update').length;
      console.log(`session/resume -> OK ${summarise(resume.result)}`);
      console.log(`  replayed ${replayed} session/update notification(s) while resuming`);
      if (resume.result?.sessionId && resume.result.sessionId !== sessionId) {
        console.log(`  NOTE: the runtime calls this session ${resume.result.sessionId}`);
      }
    }
  }

  console.log('');
  console.log(`notifications seen: ${notifications.length}`);
  const byMethod = new Map();
  for (const n of notifications) byMethod.set(n.method, (byMethod.get(n.method) ?? 0) + 1);
  for (const [method, count] of byMethod) console.log(`  ${method} x${count}`);
  console.log('');
  console.log('NO PROMPT WAS SENT: nothing here costs a model call.');
} finally {
  try { child.kill(); } catch { /* already gone */ }
}
