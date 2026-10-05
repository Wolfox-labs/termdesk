/**
 * The phone path for a CLI-shaped kernel: real server, real frames, stub CLI.
 *
 * The ACP path already has tools/phone-acp-stub-e2e.mjs. This is the same idea
 * for tier "shim": QoderWork and Command Code only have a command line, so the
 * shim adapter turns them into the same conversation the phone already knows.
 * What it proves, without spending anything:
 *
 *   - a CLI kernel with a declared manifest is selectable on the phone
 *   - one turn streams the CLI's output into the transcript
 *   - the session id the CLI named is adopted, so the SECOND turn resumes that
 *     session instead of starting a new one (this is "继续对话" for a CLI kernel)
 *   - the CLI list answers session/list, so history can list its sessions
 *
 *   node tools/phone-cli-shim-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_CLI_E2E_PORT ?? 7432);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

let failures = 0;
const check = (label, ok, detail = '') => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `  [${detail}]` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const env = {
  ...process.env,
  TERMDESK_CLI_KERNELS: JSON.stringify([
    {
      id: 'faketool',
      label: 'Fake CLI kernel',
      bin: process.execPath,
      shim: {
        newArgs: ['--print', '--output-format', 'stream-json'],
        resumeArgs: ['--print', '--output-format', 'stream-json', '--resume'],
        listArgs: ['--list-sessions'],
      },
    },
  ]),
};
// The stub CLI is node + script, exactly like the node-hosted CLIs the registry
// already resolves; the prelude travels in the manifest-free `preArgs` path.
env.TERMDESK_CLI_KERNELS = JSON.stringify(JSON.parse(env.TERMDESK_CLI_KERNELS).map((k) => ({
  ...k,
  bin: process.execPath,
  preArgs: [path.join(ROOT, 'tools', 'fake-cli-agent.mjs')],
})));

const server = spawn(process.execPath, ['src/server.js', '--host', '127.0.0.1', '--port', String(PORT)], {
  cwd: ROOT,
  env,
  stdio: ['ignore', 'pipe', 'pipe'],
});
let serverLog = '';
server.stdout.on('data', (d) => { serverLog += d.toString(); });
server.stderr.on('data', (d) => { serverLog += d.toString(); });
const shutdown = (code) => { try { server.kill(); } catch {} process.exit(code); };

async function waitForHealth(ms = 15000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/healthz`);
      if (res.ok) return true;
    } catch {}
    await sleep(200);
  }
  return false;
}

function connect() {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  const frames = [];
  ws.on('message', (raw) => {
    try { frames.push(JSON.parse(String(raw))); } catch {}
  });
  return new Promise((resolve, reject) => {
    ws.once('open', () => resolve({ ws, frames }));
    ws.once('error', reject);
  });
}

async function waitFrame(client, pred, ms, label) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const hit = client.frames.find(pred);
    if (hit) return hit;
    await sleep(50);
  }
  throw new Error(`timeout waiting for ${label}; log: ${serverLog.slice(-300)}`);
}

const assistantText = (client, chatId) =>
  client.frames
    .filter((f) => f.type === 'chat.event' && f.chatId === chatId && f.item)
    .map((f) => f.item)
    .filter((i) => i.kind === 'message' && i.role === 'assistant')
    .map((i) => i.text)
    .join('');

try {
  check('server starts', await waitForHealth());

  const body = await (await fetch(`${BASE}/kernels.json`)).json();
  const kernel = (body.kernels ?? []).find((k) => k.id === 'faketool');
  check('the CLI kernel is offered to the phone', Boolean(kernel), kernel?.tier ?? 'missing');
  check('and it is selectable once its manifest is declared', kernel?.selectable === true);

  const client = await connect();
  client.ws.send(JSON.stringify({ type: 'auth', token: TOKEN }));
  await waitFrame(client, (f) => f.type === 'auth.ok', 8000, 'auth.ok');

  client.ws.send(JSON.stringify({ type: 'chat.create', engine: 'faketool', cwd: ROOT.replace(/\\/g, '/') }));
  const chat = await waitFrame(client, (f) => f.type === 'chat' && f.id, 15000, 'chat frame');
  const chatId = chat.id;
  check('a conversation opens on the CLI kernel', chat.engine === 'faketool', `engine=${chat.engine}`);

  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: '第一条' }));
  await waitFrame(client, (f) => f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended', 60000, 'turn 1 end');
  const first = assistantText(client, chatId);
  check('the CLI output reaches the phone', first.includes('stub CLI heard'), first.slice(0, 50));

  const before = client.frames.length;
  // The session id the CLI named must have been adopted: the next turn has to
  // come back as a resume, which the stub makes visible in its own words.
  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: '第二条' }));
  // Only a turn-end frame that arrived AFTER the second send counts; the first
  // turn's frame is still in the list.
  await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= before && f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended',
    60000,
    'turn 2 end',
  );
  const whole = assistantText(client, chatId);
  check('the second turn resumed that session', whole.includes('resumed cli-0001'), whole.slice(-60));

  // The CLI's own session index feeds the history list.
  let listed = null;
  client.ws.send(JSON.stringify({ type: 'sessions.list' }));
  try {
    listed = await waitFrame(client, (f) => f.type === 'sessions', 20000, 'sessions frame');
  } catch { /* reported below */ }
  const enginesSeen = [...new Set((listed?.sessions ?? []).map((s) => s.engine))];
  const cliSessions = (listed?.sessions ?? []).filter((s) => s.engine === 'faketool');
  if (!cliSessions.length) console.log('   (acpSources=' + JSON.stringify(listed?.acpSources ?? null) + ')');
  check('the CLI session index is listed', cliSessions.length >= 2, `${cliSessions.length} session(s)`);

  client.ws.close();
  console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) failed`);
  shutdown(failures === 0 ? 0 : 1);
} catch (error) {
  console.log(`FAIL ${error.message}`);
  console.log(serverLog.slice(-1500));
  shutdown(1);
}
