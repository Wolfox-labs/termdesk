/** Isolated real WebSockets, fixed CLI/ACP replies and temporary files; no model calls. */
import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import { once } from 'node:events';
import WebSocket from 'ws';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const root = path.resolve(import.meta.dirname, '..');

test('multiple clients preserve streams, ownership and credential revocation', { timeout: 120_000 }, async t => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-multi-'));
  assert.equal(path.dirname(home), path.resolve(os.tmpdir()));
  const master = crypto.randomBytes(32).toString('base64url');
  fs.mkdirSync(path.join(home, '.termdesk'));
  fs.writeFileSync(path.join(home, '.termdesk', 'token'), master);
  const work = path.join(home, 'work');
  fs.mkdirSync(work);
  const file = path.join(work, 'proof.txt');
  fs.writeFileSync(file, 'isolated transfer');
  const portServer = net.createServer();
  portServer.listen(0, '127.0.0.1');
  await once(portServer, 'listening');
  const port = portServer.address().port;
  await new Promise(resolve => portServer.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  const absent = path.join(home, 'missing');
  const env = { ...process.env, USERPROFILE: home, HOME: home,
    TERMDESK_ROOTS: work, TERMDESK_RELAY: '0', TERMDESK_KERNELS_PROBE: '0',
    TERMDESK_CODEX: absent, TERMDESK_DSH: absent, TERMDESK_OPENCODE: absent,
    TERMDESK_MIMO: absent, TERMDESK_QODER: absent, TERMDESK_COMMAND_CODE: absent,
    TERMDESK_KERNEL_MANIFEST_DIR: path.join(home, 'kernels'),
    TERMDESK_UI_DEMO_STORE: path.join(work, 'demo.json'),
    TERMDESK_ACP_KERNELS: JSON.stringify([{ id: 'fake-acp', label: 'Fake ACP', bin: process.execPath,
      args: [path.join(root, 'tools/fake-acp-agent.mjs')] }]),
    TERMDESK_CLI_KERNELS: JSON.stringify([{ id: 'multi-demo', label: 'Fixed reply', bin: process.execPath,
      preArgs: [path.join(root, 'tools/ui-demo-assistant.mjs')],
      shim: { newArgs: ['--print'], resumeArgs: ['--print', '--resume'], listArgs: ['--list-sessions'] } }]),
  };
  const server = spawn(process.execPath, ['src/server.js', '--host', '127.0.0.1', '--port', String(port), '--enable-shell'],
    { cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let serverLog = '';
  server.stdout.on('data', bytes => { serverLog += bytes; });
  server.stderr.on('data', bytes => { serverLog += bytes; });
  const clients = [];
  t.after(async () => {
    for (const c of clients) c.ws.terminate();
    if (server.exitCode === null && process.platform === 'win32') {
      spawnSync('taskkill.exe', ['/PID', String(server.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    } else server.kill();
    if (server.exitCode === null) await Promise.race([once(server, 'exit'), pause(5000)]);
    fs.rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  });
  let healthy = false;
  for (let i = 0; i < 150; i++) {
    try { if ((await fetch(`${base}/healthz`)).ok) { healthy = true; break; } } catch { /* startup */ }
    await pause(100);
  }
  assert.ok(healthy, `isolated server failed to start: ${serverLog.slice(-1000)}`);
  async function connect(token, id, kind = 'android', accepted = true) {
    const ws = new WebSocket(base.replace('http:', 'ws:'));
    const c = { ws, frames: [], all: [], closed: null, autoApprove: false, answered: new Set(),
      send(type, payload = {}) { ws.send(JSON.stringify({ type, ...payload })); },
      clear() { this.frames.length = 0; this.all.length = 0; },
      async take(predicate, timeout = 12000) {
        const until = Date.now() + timeout;
        while (Date.now() < until) {
          const i = this.frames.findIndex(predicate);
          if (i >= 0) return this.frames.splice(i, 1)[0];
          await pause(20);
        }
        throw new Error(`frame timeout (${id}): ${this.all.slice(-6).map(f => f.type).join(', ')}`);
      },
      async close() { ws.close(); if (!this.closed) await once(ws, 'close'); },
    };
    clients.push(c);
    ws.on('close', (code) => { c.closed = code; });
    ws.on('message', bytes => {
      const f = JSON.parse(bytes.toString()); c.frames.push(f); c.all.push(f);
      if (c.autoApprove && f.type === 'chat.approval' && f.state !== 'resolved' && !c.answered.has(f.requestId)) {
        const option = f.options?.find(item => item.id === 'allow_once');
        if (option) { c.answered.add(f.requestId); c.send('chat.approve', { requestId: f.requestId, optionId: option.id }); }
      }
    });
    await once(ws, 'open');
    c.send('auth', { token, v: 1, deviceId: id, deviceName: id, clientKind: kind });
    const auth = await c.take(f => f.type === 'auth.ok' || f.type === 'auth.fail');
    assert.equal(auth.type, accepted ? 'auth.ok' : 'auth.fail');
    return c;
  }
  const pair = await (await fetch(`${base}/pair.json`)).json();
  let phone = await connect(pair.token, 'untrusted-device-label');
  let desktop = await connect(master, 'desktop-alpha', 'desktop');
  phone.send('chat.create', { engine: 'multi-demo', cwd: work });
  const created = await phone.take(f => f.type === 'chat');
  const chatId = created.id;
  assert.equal(created.ownership.owner.deviceId, pair.deviceId);
  async function turn(sender, receivers, text, id = chatId) {
    for (const c of receivers) c.clear();
    sender.send('chat.send', { chatId: id, text });
    await Promise.all(receivers.map(c => c.take(f => f.type === 'chat.turn' && f.chatId === id && f.state === 'ended')));
  }

  await t.test('phone and desktop receive the same ordered chat events and individual ownership', async () => {
    await turn(phone, [phone, desktop], 'multi-client first');
    const events = c => c.all.filter(f => f.type === 'chat.event' && f.chatId === chatId);
    assert.ok(events(phone).length > 2);
    assert.deepEqual(events(phone), events(desktop));
    assert.equal(phone.all.filter(f => f.type === 'chats').at(-1).chats.find(c => c.id === chatId).ownership.mode, 'owner');
    assert.equal(desktop.all.filter(f => f.type === 'chats').at(-1).chats.find(c => c.id === chatId).ownership.mode, 'observer');
    await phone.take(f => f.type === 'notify' && f.chatId === chatId);
    assert.equal(desktop.all.some(f => f.type === 'notify'), false);
  });

  await t.test('disconnecting the desktop leaves the existing phone stream attached', async () => {
    await desktop.close();
    await turn(phone, [phone], 'multi-client second');
    assert.ok(phone.all.some(f => f.type === 'chat.event' && f.item?.text?.includes('第 2 次')));
    desktop = await connect(master, 'desktop-beta', 'desktop');
  });

  await t.test('takeover broadcasts per-client authority and old owner writes are refused', async () => {
    phone.clear(); desktop.clear();
    desktop.send('chat.claim', { chatId, force: true });
    const ownerList = await desktop.take(f => f.type === 'chats' && f.chats.some(c => c.id === chatId && c.ownership.mode === 'owner'));
    assert.equal(ownerList.chats.find(c => c.id === chatId).ownership.owner.deviceId, 'desktop-beta');
    await phone.take(f => f.type === 'chats' && f.chats.some(c => c.id === chatId && c.ownership.mode === 'observer'));
    phone.send('chat.send', { chatId, text: 'must be refused before any fake turn starts' });
    const refusal = await phone.take(f => f.type === 'action.result' && f.action === 'chat.send');
    assert.equal(refusal.ok, false);
    assert.equal(refusal.code, 'session_busy');
  });

  await t.test('a desktop cannot consume notifications owed to an offline phone', async () => {
    const ids = phone.all.filter(f => f.type === 'notify').map(f => f.id);
    if (ids.length) phone.send('notify.ack', { ids });
    await phone.close();
    await turn(desktop, [desktop], 'offline phone notification');
    desktop.send('notify.ack', { ids: Array.from({ length: 100 }, (_, i) => `n-${i + 1}`) });
    phone = await connect(pair.token, 'same-phone-reconnected');
    const note = await phone.take(f => f.type === 'notify' && f.chatId === chatId && f.whileAway);
    assert.equal(note.kind, 'turn_done');
    assert.equal(desktop.all.some(f => f.type === 'notify'), false);
  });

  await t.test('opening another terminal does not steal subscribed shell output', async () => {
    phone.send('term.open');
    const opened = await phone.take(f => f.type === 'term.opened');
    desktop.send('term.open', { sessionId: opened.sessionId });
    await desktop.take(f => f.type === 'term.opened');
    const other = await connect(master, 'desktop-other-shell', 'desktop');
    other.send('term.open'); const otherTerminal = await other.take(f => f.type === 'term.opened');
    phone.clear(); desktop.clear(); other.clear();
    phone.send('term.run', { sessionId: opened.sessionId, command: 'echo MULTI_SHELL_PROOF' });
    await phone.take(f => f.type === 'term.exit' && f.sessionId === opened.sessionId);
    for (const c of [phone, desktop]) assert.ok(c.all.some(f => f.type === 'term.output' && f.text.includes('MULTI_SHELL_PROOF')));
    assert.equal(other.all.some(f => f.type === 'term.output' && f.sessionId === opened.sessionId), false);
    phone.send('term.close', { sessionId: opened.sessionId });
    await phone.take(f => f.type === 'action.result' && f.action === 'term.close');
    other.send('term.close', { sessionId: otherTerminal.sessionId });
    await other.take(f => f.type === 'action.result' && f.action === 'term.close');
    await other.close();
  });

  await t.test('chat terminal updates go to subscribers, not every observer', async () => {
    phone.send('chat.create', { engine: 'fake-acp', cwd: work });
    const acp = await phone.take(f => f.type === 'chat' && f.engine === 'fake-acp');
    phone.send('chat.terminals', { chatId: acp.id });
    await phone.take(f => f.type === 'chat.terminals' && f.chatId === acp.id);
    phone.autoApprove = true;
    await turn(phone, [phone, desktop], 'synthetic ACP terminal', acp.id);
    assert.ok(phone.all.some(f => f.type === 'chat.terminal.output' && f.chatId === acp.id));
    assert.equal(desktop.all.some(f => f.type === 'chat.terminal.output' && f.chatId === acp.id), false);
  });

  await t.test('device credentials work over HTTP before revocation', async () => {
    const download = await fetch(`${base}/download?path=${encodeURIComponent(file)}`, { headers: { Authorization: `Bearer ${pair.token}` } });
    assert.equal(download.status, 200);
    assert.equal(await download.text(), 'isolated transfer');
    const metadata = await fetch(`${base}/kernel/local`, { headers: { Authorization: `Bearer ${pair.token}` } });
    assert.notEqual(metadata.status, 401); // No package is required for this authentication check.
  });

  await t.test('revocation closes every connection using that credential, and blocks reconnect and HTTP', async () => {
    const duplicate = await connect(pair.token, 'different-claimed-device-id');
    const closed = Promise.all([once(phone.ws, 'close'), once(duplicate.ws, 'close')]);
    desktop.send('devices.revoke', { id: pair.deviceId });
    const result = await desktop.take(f => f.type === 'action.result' && f.action === 'devices.revoke');
    assert.equal(result.ok, true);
    const codes = await closed;
    assert.ok(codes.every(([code]) => code === 4401));
    await connect(pair.token, 'revoked-reconnect', 'android', false);
    assert.equal((await fetch(`${base}/download?path=${encodeURIComponent(file)}`, { headers: { Authorization: `Bearer ${pair.token}` } })).status, 401);
    assert.equal((await fetch(`${base}/kernel/local`, { headers: { Authorization: `Bearer ${pair.token}` } })).status, 401);
    desktop.send('kernels.list');
    await desktop.take(f => f.type === 'kernels');
  });

  await t.test('removing a device also closes existing sockets without disconnecting other devices', async () => {
    const removable = await (await fetch(`${base}/pair.json`)).json();
    const connection = await connect(removable.token, 'remove-me');
    const closed = once(connection.ws, 'close');
    const result = await (await fetch(`${base}/devices.json?remove=${encodeURIComponent(removable.deviceId)}`)).json();
    assert.ok(result.ok);
    assert.equal((await closed)[0], 4401);
    desktop.send('ping'); await desktop.take(f => f.type === 'pong');
    await connect(removable.token, 'removed-reconnect', 'android', false);
  });
});
