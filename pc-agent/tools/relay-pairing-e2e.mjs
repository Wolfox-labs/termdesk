/**
 * The relay pairing path, end to end, with a real agent.
 *
 * Everything here is real except the internet: a relay runs on loopback, the agent
 * runs as its own process in relay mode, and the "phone" is a WebSocket client that
 * pairs with a one-time code. The chain under test is the one that broke in
 * production — phone → relay → connector → agent — plus the two things the relay
 * path adds: a code per pairing, and a list of phones that can be revoked one at a
 * time.
 *
 *   node tools/relay-pairing-e2e.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createRelay } from '../../relay/src/relay.js';
import { digest } from '../../relay/src/credentials.js';

const ROOT = path.resolve(import.meta.dirname, '..');
const NODE_ID = 'test-pc';
const NODE_KEY = 'test-node-key';
const TOKEN = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

let failures = 0;
const check = (label, ok, detail = '') => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `  [${detail}]` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function freePort() {
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const { port } = probe.address();
  await new Promise((r) => probe.close(r));
  return port;
}

function inbox(ws) {
  const queue = [], waiters = [];
  ws.on('message', (raw) => {
    let f; try { f = JSON.parse(String(raw)); } catch { return; }
    const i = waiters.findIndex((w) => w.p(f));
    if (i >= 0) { const w = waiters.splice(i, 1)[0]; clearTimeout(w.timer); w.resolve(f); } else queue.push(f);
  });
  const wait = (type, p = () => true, ms = 8000) => {
    const predicate = (f) => f.type === type && p(f);
    const i = queue.findIndex(predicate);
    if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { p: predicate, resolve, timer: null };
      w.timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error(`等不到 ${type}`)); }, ms);
      waiters.push(w);
    });
  };
  return { wait };
}

const relayConfig = () => ({
  version: 1,
  nodes: [{ id: NODE_ID, label: '测试机', keyHash: digest(NODE_KEY) }],
  devices: [],
  pairings: [],
});

const relay = createRelay({ config: relayConfig() });
const relayPort = await freePort();
relay.server.listen(relayPort, '127.0.0.1');
await once(relay.server, 'listening');
const relayUrl = `ws://127.0.0.1:${relayPort}`;

const agentPort = await freePort();
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-relay-e2e-'));
const configPath = path.join(dir, 'relay.json');
fs.writeFileSync(configPath, JSON.stringify({ url: relayUrl, nodeId: NODE_ID, key: NODE_KEY }));

const agent = spawn(process.execPath, ['src/server.js', '--host', '127.0.0.1', '--port', String(agentPort)], {
  cwd: ROOT,
  env: { ...process.env, TERMDESK_RELAY: '1', TERMDESK_RELAY_CONFIG: configPath },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
agent.stdout.on('data', (d) => { log += d.toString(); });
agent.stderr.on('data', (d) => { log += d.toString(); });

const cleanup = async () => {
  try { agent.kill(); } catch { /* already gone */ }
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* best effort */ }
  await relay.close().catch(() => {});
};
process.on('exit', () => { try { agent.kill(); } catch { /* ignore */ } });

const base = `http://127.0.0.1:${agentPort}`;
async function waitForHealth(ms = 20_000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try { if ((await fetch(`${base}/healthz`)).ok) return true; } catch { /* not up yet */ }
    await sleep(200);
  }
  return false;
}
async function waitForLog(pattern, ms = 20_000) {
  const deadline = Date.now() + ms;
  const re = new RegExp(pattern);
  while (Date.now() < deadline) {
    const hit = log.match(re);
    if (hit) return hit;
    await sleep(100);
  }
  return null;
}
const json = async (route) => (await fetch(base + route)).json();

function phone() {
  const ws = new WebSocket(relayUrl + '/client', { maxPayload: 8 * 1024 * 1024 });
  const read = inbox(ws);
  return { ws, read, ready: once(ws, 'open'), closed: once(ws, 'close').then(([code]) => code) };
}

try {
  check('代理起来了', await waitForHealth(), `port ${agentPort}`);

  // The banner is what a person sees first: no code means no way to pair.
  const banner = await waitForLog('配对码 ([0-9A-Z-]+)');
  check('启动横幅里有配对码', Boolean(banner), banner?.[1] ?? log.slice(-200));
  check('横幅说明了中转地址', log.includes(relayUrl));
  check('横幅给出配对页与名单入口', log.includes('/pair') && log.includes('/devices'));

  const pair = await json('/pair.json');
  check('/pair.json 走中转', pair.relay === true && pair.url === relayUrl, String(pair.url));
  check('并且给的是码，不是这台电脑的令牌', pair.code !== TOKEN && pair.token === pair.code);
  check('二维码里是同一次性的码', pair.payload.includes(encodeURIComponent(pair.code)));
  check('二维码说明了对端是中转（手机据此知道解绑要通知谁）', pair.payload.includes('relay=1'), pair.payload.slice(0, 80));
  const again = await json('/pair.json');
  check('每次打开配对页都是一个新码', again.code !== pair.code, `${pair.code} → ${again.code}`);

  // The phone: pairs with the code, and the agent itself answers it.
  const first = phone();
  await first.ready;
  first.ws.send(JSON.stringify({ type: 'auth', token: pair.code, device: { name: '小米 15' } }));
  const paired = await first.read.wait('device.paired');
  check('手机用配对码换到了自己的凭据', typeof paired.token === 'string' && paired.token.length >= 32);
  const authOk = await first.read.wait('auth.ok').catch((err) => err);
  check('回答鉴权的是这台电脑的代理，不是中转', authOk?.hostname === os.hostname(), String(authOk?.hostname ?? authOk?.message));

  // No silent takeover: a second phone is told, not allowed to push the first out.
  const second = phone();
  await second.ready;
  second.ws.send(JSON.stringify({ type: 'auth', token: paired.token }));
  const closeCode = await second.closed.catch(() => null);
  check('第二台手机被明确拒绝（4409），不是顶掉第一台', closeCode === 4409, String(closeCode));

  const devices = await json('/devices.json');
  check('名单里能看到这台手机，并认得名字', devices.devices.some((d) => d.label === '小米 15'), JSON.stringify(devices.devices.map((d) => d.label)));
  check('名单走的是中转', devices.relay === true && devices.relayUrl === relayUrl);

  const target = devices.devices.find((d) => d.label === '小米 15');
  const revoked = await json(`/devices.json?revoke=${encodeURIComponent(target.id)}`);
  check('吊销一台手机不改动别的记录', revoked.devices.filter((d) => d.id === target.id && d.revoked).length === 1, JSON.stringify(revoked.devices));
  const page = await (await fetch(`${base}/devices`)).text();
  check('名单页面上有吊销动作与说明', page.includes('小米 15') && page.includes('已吊销'));

  // The revoked phone is out on its next connect — the whole point of per-phone credentials.
  first.ws.close();
  await sleep(200);
  const third = phone();
  await third.ready;
  third.ws.send(JSON.stringify({ type: 'auth', token: paired.token }));
  const refused = await third.read.wait('auth.fail').catch(() => null);
  check('被吊销的手机再也连不上', refused?.reason === 'invalid credentials', String(refused?.reason));
  third.ws.close();
} catch (err) {
  failures += 1;
  console.log(`FAIL  ${err?.message ?? err}`);
  console.log(log.slice(-600));
} finally {
  await cleanup();
}

console.log(`\n${failures === 0 ? 'relay pairing e2e: ALL PASS' : `${failures} failed`}`);
process.exit(failures === 0 ? 0 : 1);
