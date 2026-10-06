/**
 * One real Codex turn, to check that a tool call is visible and actually runs.
 *
 * This is the one check in the repository that SPENDS MONEY: it sends a prompt to
 * a real model through the Codex kernel. It is deliberately not part of
 * `npm test` and must only be run with the owner's permission, naming the
 * (agent, model) pair — see documents/开发约束.md.
 *
 * What it asserts, with the same frames the phone sends:
 *   - the conversation opens on Codex with the named provider/model;
 *   - the turn produces a tool item, and the tool result carries the marker the
 *     command printed — i.e. the command really ran on this machine;
 *   - the turn ends cleanly, and the transcript the phone renders is printed.
 *
 *   node tools/codex-toolcall-check.mjs --list
 *   node tools/codex-toolcall-check.mjs --port 7421 --provider <id> --model qwen3.8-flash
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const args = process.argv.slice(2);
const flag = (name, fallback = null) => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 ? args[at + 1] : fallback;
};
const PORT = Number(flag('port', process.env.TERMDESK_PORT ?? 7421));
const HOST = flag('host', '127.0.0.1');
const PROVIDER = flag('provider', null);
const MODEL = flag('model', null);
const LIST_ONLY = args.includes('--list');
const MARKER = 'termdesk-toolcall-ok';
const CWD = flag('cwd', process.cwd());

const TOKEN = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const failures = [];
const check = (label, ok, detail = '') => {
  if (!ok) failures.push(label);
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `  [${detail}]` : ''}`);
};

const ws = new WebSocket(`ws://${HOST}:${PORT}`);
const frames = [];
ws.on('message', (raw) => {
  try { frames.push(JSON.parse(String(raw))); } catch { /* ignore */ }
});
await new Promise((resolve, reject) => {
  ws.once('open', resolve);
  ws.once('error', reject);
});
const send = (value) => ws.send(JSON.stringify(value));
const waitFor = async (pred, ms, label) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const hit = frames.find(pred);
    if (hit) return hit;
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${label}`);
};

send({ type: 'auth', token: TOKEN });
await waitFor((f) => f.type === 'auth.ok', 8000, 'auth.ok');

if (LIST_ONLY) {
  send({ type: 'codex.get' });
  const config = await waitFor((f) => f.type === 'codex.config', 15000, 'codex.config');
  const c = config.config ?? {};
  console.log(`config   ${c.configPath}`);
  console.log(`current  model=${c.model} provider=${c.modelProvider} effort=${c.reasoningEffort}`);
  console.log('\nproviders:');
  for (const p of c.providers ?? []) {
    console.log(`  ${String(p.id).padEnd(12)} token=${p.hasToken ? 'yes' : 'no '}  ${p.baseUrl}`);
  }
  console.log('\nmodels:');
  for (const m of c.models ?? []) console.log(`  ${m.slug}`);
  ws.close();
  process.exit(0);
}

if (!PROVIDER || !MODEL) {
  console.error('需要 --provider 与 --model（先用 --list 看这台机器的 Codex 配置）');
  process.exit(2);
}

console.log(`provider=${PROVIDER} model=${MODEL} cwd=${CWD}\n`);

send({
  type: 'chat.create',
  engine: 'codex',
  cwd: CWD.replace(/\\/g, '/'),
  provider: PROVIDER,
  model: MODEL,
  title: 'tool call check',
});
const chat = await waitFor((f) => f.type === 'chat' && f.id, 30000, 'the conversation');
const chatId = chat.id;
check('the conversation opens on Codex', chat.engine === 'codex', `engine=${chat.engine}`);
check('it runs the model we named', String(chat.model ?? '').includes(MODEL), `model=${chat.model} provider=${chat.provider}`);

const prompt = `请只做一件事：在 shell 里执行命令 echo ${MARKER} ，然后把它的输出原样告诉我。不要做别的。`;
console.log(`prompt: ${prompt}\n`);
send({ type: 'chat.send', chatId, text: prompt });

// Approvals, if the kernel asks (this machine's Codex usually does not).
const approvalDeadline = Date.now() + 120_000;
while (Date.now() < approvalDeadline) {
  const pending = frames.find((f) => f.type === 'chat.approval' && f.chatId === chatId && f.state !== 'resolved');
  if (pending) {
    console.log(`approval requested: ${pending.title ?? ''} → allow_once`);
    send({ type: 'chat.approve', requestId: pending.requestId, optionId: 'allow_once' });
    break;
  }
  if (frames.some((f) => f.type === 'chat.turn' && f.chatId === chatId && ['ended', 'failed'].includes(f.state))) break;
  await sleep(500);
}

const turn = await waitFor(
  (f) => f.type === 'chat.turn' && f.chatId === chatId && ['ended', 'failed'].includes(f.state),
  20 * 60_000,
  'the turn to end',
);

const items = frames
  .filter((f) => f.type === 'chat.event' && f.chatId === chatId && f.item)
  .map((f) => f.item);
const tools = items.filter((i) => i.kind === 'tool' || i.kind === 'tool_result');
const assistant = items.filter((i) => i.kind === 'message' && i.role === 'assistant').map((i) => i.text).join('');

check('the turn ends cleanly', turn.state === 'ended', String(turn.state));
check('a tool call is visible to the phone', tools.length > 0, `${tools.length} tool item(s)`);
check(
  'the command really ran on this machine',
  tools.some((i) => String(i.text ?? '').includes(MARKER)),
  tools.map((i) => String(i.text ?? '').slice(0, 60)).join(' | ').slice(0, 160),
);
check('the agent answered', assistant.length > 0, assistant.slice(0, 80));

console.log('\ntranscript the phone renders:');
for (const item of items) {
  const text = String(item.text ?? '').replace(/\s+/g, ' ').slice(0, 80);
  console.log(`  ${String(item.kind).padEnd(12)} ${String(item.role ?? '').padEnd(9)} ${text}`);
}

ws.close();
console.log(failures.length === 0 ? '\nall checks passed' : `\n${failures.length} check(s) failed`);
process.exit(failures.length === 0 ? 0 : 1);
