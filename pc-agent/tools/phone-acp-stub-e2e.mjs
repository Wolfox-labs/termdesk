/**
 * The whole phone path, with a stub kernel and no model call.
 *
 * Why this exists: the owner reported "I sent a message and the phone showed
 * nothing". Every part had a unit test - the ACP mapping, the stream merge, the
 * approval broker - and none of them covered the thing that broke, which is the
 * assembled route: real server process -> real WebSocket -> real frames -> what
 * the phone renders from them. This test drives that route with
 * tools/fake-acp-agent.mjs, so it costs nothing and can run on every change.
 *
 * It asserts what a person would check by hand:
 *   - the kernel shows up in /kernels.json (the phone's picker reads this)
 *   - a turn produces assistant text that stays on screen, and the last frame
 *     for it says the text is final rather than a replaceable preview
 *   - the tool call is visible as an item, not swallowed
 *   - the permission question reaches the phone and the answer resumes the turn
 *   - a second message in the same conversation also answers (the failure that
 *     looked like "it only works once")
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const ROOT = path.resolve(import.meta.dirname, '..');
const PORT = Number(process.env.TERMDESK_E2E_PORT ?? 7431);
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
  TERMDESK_ACP_KERNELS: JSON.stringify([
    {
      id: 'stub',
      label: 'Stub kernel (free)',
      bin: process.execPath,
      args: [path.join(ROOT, 'tools', 'fake-acp-agent.mjs')],
    },
  ]),
};

const server = spawn(
  process.execPath,
  ['src/server.js', '--host', '127.0.0.1', '--port', String(PORT)],
  { cwd: ROOT, env, stdio: ['ignore', 'pipe', 'pipe'] },
);
let serverLog = '';
server.stdout.on('data', (d) => { serverLog += d.toString(); });
server.stderr.on('data', (d) => { serverLog += d.toString(); });

const shutdown = (code) => {
  try { server.kill(); } catch {}
  process.exit(code);
};

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

/** A client that keeps every frame, so assertions never depend on timing. */
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
  throw new Error(`timeout waiting for ${label} (${ms} ms); server log: ${serverLog.slice(-400)}`);
}

const items = (client, chatId) =>
  client.frames
    .filter((f) => f.type === 'chat.event' && f.chatId === chatId && f.item)
    .map((f) => f.item);

try {
  check('server starts', await waitForHealth());

  const kernelsRes = await fetch(`${BASE}/kernels.json`);
  const kernelsBody = await kernelsRes.json();
  const stub = (kernelsBody.kernels ?? []).find((k) => k.id === 'stub');
  check('the stub kernel is offered to the phone', Boolean(stub), stub ? `tier=${stub.tier}` : 'missing');

  const client = await connect();
  client.ws.send(JSON.stringify({ type: 'auth', token: TOKEN }));
  await waitFrame(client, (f) => f.type === 'auth.ok', 8000, 'auth.ok');

  client.ws.send(JSON.stringify({ type: 'chat.create', engine: 'stub', cwd: ROOT.replace(/\\/g, '/') }));
  const chat = await waitFrame(client, (f) => f.type === 'chat' && f.id, 15000, 'chat frame');
  const chatId = chat.id;
  check('the conversation opens on the stub kernel', chat.engine === 'stub', `engine=${chat.engine}`);

  // ---- the model list, asked for before any turn ---------------------------
  // The phone shows the picker as soon as a conversation is open, so the list
  // has to exist then - not after the first answer. The session opened to answer
  // this question is the one turn 1 then uses: if that were not so, every chat
  // would pay a second, empty session in the kernel's own history.
  client.ws.send(JSON.stringify({ type: 'chat.models', chatId }));
  const modelsFrame = await waitFrame(
    client,
    (f) => f.type === 'chat.models' && f.chatId === chatId,
    20000,
    'the model list',
  );
  check('the model list arrives before the first turn', modelsFrame.supported === true, JSON.stringify(modelsFrame).slice(0, 120));
  check('the list carries what the kernel declared', (modelsFrame.models ?? []).some((m) => m.id === 'stub/echo-1'), JSON.stringify(modelsFrame.models));
  check('the list names the current model', modelsFrame.current === 'stub/echo-1', String(modelsFrame.current));
  check('the permission modes ride along', (modelsFrame.modes?.availableModes ?? []).length > 0, JSON.stringify(modelsFrame.modes));
  check('no second session was opened for it', modelsFrame.sessionId === 'stub-0001', String(modelsFrame.sessionId));

  // ---- turn 1 --------------------------------------------------------------
  const ask = '走一遍完整链路';
  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: ask }));

  const permission = await waitFrame(
    client,
    (f) => f.type === 'chat.approval' && f.chatId === chatId && f.state !== 'resolved',
    30000,
    'the permission question',
  );
  check('the permission question reaches the phone', Boolean(permission.requestId));
  check(
    'the question offers real choices',
    (permission.options ?? []).some((o) => o.id === 'allow_once'),
    (permission.options ?? []).map((o) => o.id).join(','),
  );

  client.ws.send(JSON.stringify({ type: 'chat.approve', requestId: permission.requestId, optionId: 'allow_once' }));
  const approved = await waitFrame(
    client,
    (f) => f.type === 'action.result' && f.action === 'chat.approve' && f.target === permission.requestId,
    10000,
    'the approve ack',
  );
  check('the phone answer is accepted', approved.ok === true, approved.code);

  await waitFrame(
    client,
    (f) => f.type === 'chat.turn' && f.chatId === chatId && ['ended', 'failed'].includes(f.state),
    60000,
    'the turn to end',
  );

  const after = items(client, chatId);
  const assistant = after.filter((i) => i.kind === 'message' && i.role === 'assistant');
  const answerText = assistant.map((i) => i.text).join('');
  check('the answer is on screen', answerText.includes('Stub kernel received'), answerText.slice(0, 60));
  check('the answer is not a replaceable preview', assistant.some((i) => i.streaming === false));
  check('the tool call is visible', after.some((i) => i.kind === 'tool'), `${after.filter((i) => i.kind === 'tool').length} tool item(s)`);
  // ACP concludes a turn with the chat.turn frame (the DSH/Codex path also
  // writes a `turn` transcript item; ACP does not, and inventing one here would
  // be asserting a shape the phone never sees).
  check(
    'the turn ends cleanly',
    client.frames.some((f) => f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended'),
  );
  check(
    'the approval is written into the transcript',
    after.some((i) => String(i.name ?? '') === 'permission'),
  );

  // ---- turn 2: the same conversation, one more message ---------------------
  // Asked and answered the way turn 1 was, because "it only answered the first
  // time" is exactly the shape of the bug this test exists for.
  const before = client.frames.length;
  const secondPermission = () =>
    client.frames
      .slice(before)
      .find((f) => f.type === 'chat.approval' && f.chatId === chatId && f.state !== 'resolved');

  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: '再来一条' }));
  await waitFrame(client, () => Boolean(secondPermission()), 30000, 'the second permission question');
  client.ws.send(
    JSON.stringify({ type: 'chat.approve', requestId: secondPermission().requestId, optionId: 'allow_once' }),
  );
  await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= before && f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended',
    60000,
    'the second turn to end',
  );
  const second = items(client, chatId).filter((i) => i.kind === 'message' && i.role === 'assistant');
  check('a second message also answers', second.length >= 2, `${second.length} assistant message(s)`);

  // ---- the conversation's command lines ------------------------------------
  // An ACP kernel runs its commands through the client, so these terminals are
  // ours and the phone must be able to list them, read them and type into them.
  //
  // Every assertion below looks only at frames that arrived AFTER its own
  // request: the same list is pushed whenever a terminal changes, so scanning
  // from the start of the connection finds the oldest copy — which is how a
  // finished terminal got read as "still running".
  const listFrom = client.frames.length;
  client.ws.send(JSON.stringify({ type: 'chat.terminals', chatId }));
  const terminalsFrame = await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= listFrom
      && f.type === 'chat.terminals'
      && f.chatId === chatId
      && (f.terminals ?? []).length > 0,
    15000,
    'the terminal list',
  );
  const first = (terminalsFrame.terminals ?? [])[0];
  check('the conversation lists the command it ran', Boolean(first), JSON.stringify(terminalsFrame.terminals).slice(0, 140));
  check('the terminal belongs to us, so it can be driven', first?.origin === 'kernel', String(first?.origin));
  check('it reports its own state', first?.state === 'exited', String(first?.state));

  const readFrom = client.frames.length;
  client.ws.send(JSON.stringify({ type: 'chat.terminal.read', chatId, terminalId: first.id }));
  const readFrame = await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= readFrom && f.type === 'chat.terminal' && f.terminalId === first.id,
    15000,
    'the terminal output',
  );
  check(
    'its output is readable',
    String(readFrame.output ?? '').includes('stub terminal says hello'),
    JSON.stringify(readFrame.output).slice(0, 80),
  );

  // ---- a terminal that is still running: the write path --------------------
  const beforeLive = client.frames.length;
  client.ws.send(JSON.stringify({ type: 'chat.send', chatId, text: 'long-terminal' }));
  const livePermission = await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= beforeLive && f.type === 'chat.approval' && f.chatId === chatId && f.state !== 'resolved',
    30000,
    'the live terminal turn to ask permission',
  );
  client.ws.send(JSON.stringify({ type: 'chat.approve', requestId: livePermission.requestId, optionId: 'allow_once' }));
  await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= beforeLive && f.type === 'chat.turn' && f.chatId === chatId && f.state === 'ended',
    60000,
    'the live terminal turn to end',
  );

  const runningFrom = client.frames.length;
  client.ws.send(JSON.stringify({ type: 'chat.terminals', chatId }));
  const runningFrame = await waitFrame(
    client,
    (f) => client.frames.indexOf(f) >= runningFrom
      && f.type === 'chat.terminals'
      && f.chatId === chatId
      && (f.terminals ?? []).some((t) => t.state === 'running'),
    20000,
    'a terminal that is still running',
  );
  const live = (runningFrame.terminals ?? []).find((t) => t.state === 'running');
  check('a still-running terminal is listed', Boolean(live), JSON.stringify(runningFrame.terminals).slice(0, 140));
  check('and it is offered as writable', live?.canWrite === true);

  client.ws.send(JSON.stringify({ type: 'chat.terminal.input', chatId, terminalId: live.id, data: 'ping\n' }));
  const echoed = await waitFrame(
    client,
    (f) => f.type === 'chat.terminal.output' && f.terminalId === live.id && String(f.chunk ?? '').includes('echo:ping'),
    20000,
    'the terminal to echo what we typed',
  );
  check('typing reaches the process', String(echoed.chunk).includes('echo:ping'));

  client.ws.send(JSON.stringify({ type: 'chat.terminal.stop', chatId, terminalId: live.id }));
  const stopped = await waitFrame(
    client,
    (f) => f.type === 'action.result' && f.action === 'chat.terminal.stop' && f.target === live.id,
    15000,
    'the stop acknowledgement',
  );
  check('stopping it is acknowledged', stopped.ok === true, stopped.message ?? stopped.code);

  // Writing to a terminal that is gone must say so, not pretend to have typed.
  client.ws.send(JSON.stringify({ type: 'chat.terminal.input', chatId, terminalId: live.id, data: 'too late\n' }));
  const refused = await waitFrame(
    client,
    (f) => f.type === 'action.result' && f.action === 'chat.terminal.input' && f.target === live.id,
    15000,
    'the refusal',
  );
  check('typing into a dead terminal is refused with a reason', refused.ok === false && Boolean(refused.message), refused.message);

  // ---- what the phone would draw -------------------------------------------
  console.log('\ntranscript the phone renders:');
  for (const item of items(client, chatId)) {
    const text = String(item.text ?? '').replace(/\s+/g, ' ').slice(0, 70);
    console.log(`  ${String(item.kind).padEnd(12)} ${String(item.role ?? '').padEnd(9)} ${text}`);
  }

  client.ws.close();
  console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) failed`);
  shutdown(failures === 0 ? 0 : 1);
} catch (error) {
  console.log(`FAIL ${error.message}`);
  console.log(serverLog.slice(-2000));
  shutdown(1);
}