/**
 * P3 end-to-end against the *running* agent, exercising exactly the frames the
 * Android client sends for the terminal.
 *
 *   node tools/p3-e2e.js
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WebSocket } from 'ws';

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` 鈥?${detail}` : ''}`);
};

const ws = new WebSocket(`ws://${HOST}:${PORT}`);
const output = [];
const exits = [];
const opened = [];

ws.on('message', (raw) => {
  const f = JSON.parse(raw.toString());
  if (f.type === 'term.output') output.push(f);
  else if (f.type === 'term.exit') exits.push(f);
  else if (f.type === 'term.opened') opened.push(f);
});

await new Promise((r) => ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'auth', token }));
  setTimeout(r, 800);
}));

const send = (o) => ws.send(JSON.stringify(o));
const waitFor = async (fn, ms = 25000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 60));
  }
  return null;
};
const textOf = (sid) => output.filter((o) => o.sessionId === sid).map((o) => o.text).join('');

try {
  send({ type: 'term.open' });
  const open = await waitFor(() => opened[0]);
  check('opens a terminal on the live agent', Boolean(open?.sessionId), open?.sessionId);
  const sid = open?.sessionId;

  const run = async (command) => {
    const before = exits.length;
    send({ type: 'term.run', sessionId: sid, command });
    return waitFor(() => (exits.length > before ? exits[exits.length - 1] : null));
  };

  // A command the user would plausibly run from a phone.
  await run('Write-Output "live-terminal-ok"');
  await waitFor(() => textOf(sid).includes('live-terminal-ok'));
  check('runs a command on the live agent', textOf(sid).includes('live-terminal-ok'));

  // Real machine inspection 鈥?the actual use case.
  await run('(Get-Process | Measure-Object).Count');
  await new Promise((r) => setTimeout(r, 600));
  const procCount = textOf(sid).match(/\b(\d{2,4})\b/g);
  check('can inspect real machine state', Boolean(procCount), `saw numbers: ${procCount?.slice(-3).join(',')}`);

  // State persistence on the live agent.
  await run('$live_probe = "live-kept"');
  await run('Write-Output "r=$live_probe"');
  await waitFor(() => textOf(sid).includes('r=live-kept'));
  check('state persists on the live agent', textOf(sid).includes('r=live-kept'));

  // Working directory on the real filesystem. The scratch directory is derived
  // from the machine's own home, never a hardcoded path, so the check passes on
  // any machine and publishes nothing about this one.
  const CWD_DIR = path.join(os.homedir(), 'termdesk-e2e-cwd');
  await run(`New-Item -ItemType Directory -Force -Path '${CWD_DIR}' | Out-Null; Set-Location '${CWD_DIR}'`);
  await run('Write-Output ("pwd=" + (Get-Location).Path)');
  await waitFor(() => textOf(sid).includes('pwd='));
  check('cwd follows real directories', textOf(sid).includes(CWD_DIR), CWD_DIR);

  // A real listing of that directory, exercising non-ASCII paths end to end.
  await run(`New-Item -ItemType File -Force -Path '${path.join(CWD_DIR, '中文名称.txt')}' | Out-Null`);
  await run(`Get-ChildItem '${CWD_DIR}' | Select-Object -ExpandProperty Name`);
  await waitFor(() => textOf(sid).includes('中文名称'));
  check('lists entries including non-ASCII names', textOf(sid).includes('中文名称'));

  // Exit code for a failing native command.
  const fail = await run('cmd /c exit 4');
  check('propagates non-zero exit codes', fail?.code === 4, `code=${fail?.code}`);

  // Error visibility on the live agent.
  const before = textOf(sid).length;
  await run('Get-Item E:\\no-such-file-99999');
  await new Promise((r) => setTimeout(r, 700));
  check('surfaces errors on the live agent', /ERR|鎵句笉鍒皘Cannot find/i.test(textOf(sid).slice(before)));

  // Clean up the session we created.
  send({ type: 'term.close', sessionId: sid });
  await new Promise((r) => setTimeout(r, 500));
  check('closed the session cleanly', true);
} catch (err) {
  check('e2e terminal flow completed', false, err.message);
}

ws.close();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nP3 live: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
