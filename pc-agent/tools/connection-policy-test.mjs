import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { once } from 'node:events';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';

const serverFile = fileURLToPath(new URL('../src/server.js', import.meta.url));

test('legacy tunnel flag is rejected before listening or starting a tunnel', () => {
  const run = spawnSync(process.execPath, [serverFile, '--tunnel'], {
    encoding: 'utf8', timeout: 10000, windowsHide: true,
  });
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /Cloudflare 隧道已移除/);
});

test('pairing works directly without any tunnel binary and exposes honest transport state', { timeout: 25000 }, async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-connection-policy-'));
  const listener = net.createServer().listen(0, '127.0.0.1');
  await once(listener, 'listening');
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  let child, ws;
  try {
    child = spawn(process.execPath, [serverFile, '--host', '127.0.0.1', '--port', String(port)], {
      env: { ...process.env, HOME: home, USERPROFILE: home, TERMDESK_RELAY: '0', TERMDESK_KERNELS_PROBE: '0' },
      stdio: 'ignore', windowsHide: true,
    });
    const base = `http://127.0.0.1:${port}`;
    let ready = false;
    for (let i = 0; i < 120; i++) {
      if (child.exitCode !== null) throw new Error('Isolated Agent exited before listening');
      try { ready = (await fetch(`${base}/healthz`, { signal: AbortSignal.timeout(1000) })).ok; } catch { }
      if (ready) break;
      await new Promise(resolve => setTimeout(resolve, 60));
    }
    assert.ok(ready, 'Isolated Agent should be reachable');
    const status = await (await fetch(`${base}/status.json`)).json();
    assert.equal(status.connections.lan.supported, true);
    assert.equal(status.connections.lan.encrypted, false);
    assert.equal(status.connections.p2p.supported, false);
    assert.equal(status.connections.relay.configured, false);
    assert.equal('tunnel' in status, false);
    const pair = await (await fetch(`${base}/pair.json`)).json();
    assert.equal(pair.ok, true);
    assert.match(pair.url, /^ws:\/\//);
    assert.ok(['lan', 'loopback'].includes(pair.urlReason));
    assert.equal('cloudflared' in pair, false);
    assert.equal('tunnel' in pair, false);
    const page = await (await fetch(`${base}/pair`)).text();
    assert.match(page, /局域网直连/);
    assert.doesNotMatch(page, /Cloudflare|公网隧道|trycloudflare/);
    ws = new WebSocket(`ws://127.0.0.1:${port}`);
    await once(ws, 'open');
    const authenticated = new Promise(resolve => ws.on('message', raw => {
      const frame = JSON.parse(raw);
      if (frame.type === 'auth.ok') resolve(frame);
    }));
    ws.send(JSON.stringify({ type: 'auth', token: pair.token, deviceId: 'direct-test', v: 1 }));
    await authenticated;
  } finally {
    ws?.terminate();
    if (child && child.exitCode === null) { child.kill(); await once(child, 'exit'); }
    fs.rmSync(home, { recursive: true, force: true });
  }
});
