/**
 * Local mode: the same agent, hosted by something that is not a desktop.
 *
 * This is the phone's situation, reproduced on this machine for free: a host with
 * no Windows install paths, one browsable root, a bash shell, and no pairing page
 * to serve. Everything here runs offline - no kernel, no model, no phone.
 *
 * A machine without bash skips (the shell branch needs one) rather than passing
 * on an untested claim.
 *
 *   node tools/local-mode-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = 7441;

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const bash = ['C:/Program Files/Git/bin/bash.exe', 'C:/Program Files/Git/usr/bin/bash.exe', '/bin/bash']
  .find((c) => fs.existsSync(c));
if (!bash) {
  console.log('no bash on this machine - local mode needs one, skipping (not a pass)');
  process.exit(0);
}

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-local-home-'));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-local-root-'));
fs.writeFileSync(path.join(root, 'hello.txt'), 'local-mode-can-read-this\n');

const agent = spawn(process.execPath, [AGENT, '--local', '--enable-shell', '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  env: {
    ...process.env,
    USERPROFILE: home,
    HOME: home,
    TERMDESK_ROOTS: root,
    TERMDESK_POSIX_SHELL: '1',
    TERMDESK_SHELL_CWD: root,
    TERMDESK_SHELL: bash,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let banner = '';
agent.stdout.on('data', (d) => { banner += d.toString(); });
agent.stderr.on('data', (d) => { banner += d.toString(); });

const waitForPort = async (ms = 15000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const ws = await new Promise((resolve, reject) => {
        const s = new WebSocket(`ws://127.0.0.1:${PORT}`);
        s.on('open', () => resolve(s));
        s.on('error', reject);
      });
      return ws;
    } catch { await new Promise((r) => setTimeout(r, 250)); }
  }
  return null;
};

const frames = [];
const ws = await waitForPort();
if (!ws) {
  console.log('the agent never came up\n' + banner.slice(0, 600));
  agent.kill();
  process.exit(1);
}
ws.on('message', (raw) => { try { frames.push(JSON.parse(String(raw))); } catch { /* ignore */ } });
const waitFrame = async (pred, ms, what) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const hit = frames.find(pred);
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error('timeout waiting for ' + what);
};

// 1. the token belongs to the host it runs on, not to a shared Windows profile
const tokenFile = path.join(home, '.termdesk', 'token');
check('the token is written under the host\'s own home', fs.existsSync(tokenFile), tokenFile);
ws.send(JSON.stringify({ type: 'auth', token: fs.readFileSync(tokenFile, 'utf8').trim() }));
await waitFrame((f) => f.type === 'auth.ok', 8000, 'auth.ok');
check('a client authenticates against it', true);

// 2. desktop-only surfaces are absent, and say why
const pairStatus = await new Promise((resolve) => {
  http.get({ host: '127.0.0.1', port: PORT, path: '/pair.json' }, (res) => {
    let body = '';
    res.on('data', (c) => { body += c; });
    res.on('end', () => resolve({ code: res.statusCode, body }));
  }).on('error', () => resolve({ code: 0, body: '' }));
});
check('there is no pairing page in local mode', pairStatus.code === 404, String(pairStatus.code));
check('and the refusal explains itself', /本地内核/.test(pairStatus.body), pairStatus.body.slice(0, 80));

// 3. status: bound to loopback, one root
const status = await new Promise((resolve) => {
  http.get({ host: '127.0.0.1', port: PORT, path: '/status.json' }, (res) => {
    let body = '';
    res.on('data', (c) => { body += c; });
    res.on('end', () => { try { resolve(JSON.parse(body)); } catch { resolve(null); } });
  }).on('error', () => resolve(null));
});
check('it listens on loopback only', status?.host === '127.0.0.1', String(status?.host));
check('it reports exactly the root it was given',
  Array.isArray(status?.roots) && status.roots.length === 1 && status.roots[0] === root,
  JSON.stringify(status?.roots));

// 4. files of the host it runs on
ws.send(JSON.stringify({ type: 'fs.roots' }));
const rootsFrame = await waitFrame((f) => f.type === 'fs.roots', 6000, 'fs.roots');
check('the client is told the same root', rootsFrame.roots?.[0] === root, JSON.stringify(rootsFrame.roots));

ws.send(JSON.stringify({ type: 'fs.list', path: root }));
const listing = await waitFrame((f) => f.type === 'fs.listing', 8000, 'fs.listing');
check('the directory listing comes from this host',
  JSON.stringify(listing).includes('hello.txt'), JSON.stringify(listing).slice(0, 120));

ws.send(JSON.stringify({ type: 'fs.read', path: path.join(root, 'hello.txt') }));
const file = await waitFrame((f) => f.type === 'fs.file', 8000, 'fs.file');
check('a file can be read back', String(file.text ?? '').includes('local-mode-can-read-this'), String(file.text ?? '').trim());

// 5. the shell is this host's shell
ws.send(JSON.stringify({ type: 'term.open' }));
const opened = await waitFrame((f) => f.type === 'term.opened', 10000, 'term.opened');
check('a terminal session opens', Boolean(opened.sessionId), JSON.stringify(opened).slice(0, 80));
ws.send(JSON.stringify({ type: 'term.run', sessionId: opened.sessionId, command: 'cat hello.txt' }));
const exit = await waitFrame((f) => f.type === 'term.exit' && f.sessionId === opened.sessionId, 20000, 'term.exit');
check('the shell runs on this host and can see its files',
  frames.some((f) => f.type === 'term.output' && String(f.text ?? '').includes('local-mode-can-read-this')),
  JSON.stringify(exit).slice(0, 120));
check('the command reports its own exit code', exit.code === 0, String(exit.code));

// 6. the kernel table tells the truth about a host with nothing installed
ws.send(JSON.stringify({ type: 'kernels.list' }));
const kernels = await waitFrame((f) => f.type === 'kernels', 15000, 'kernels');
const list = kernels.kernels ?? [];
check('the kernel table answers', list.length > 0, `${list.length}`);
check('nothing is offered that this host cannot run',
  list.every((k) => !k.available || k.selectable === true), list.map((k) => `${k.id}=${k.available}`).join(' '));

check('the banner says what this is', /本地内核/.test(banner), banner.split('\n').find((l) => l.includes('本地内核')) ?? '');

ws.close();
agent.kill();
await new Promise((r) => setTimeout(r, 300));
fs.rmSync(root, { recursive: true, force: true });
fs.rmSync(home, { recursive: true, force: true });

const failures = results.filter((r) => !r.passed).length;
console.log(`\nLocal mode: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
