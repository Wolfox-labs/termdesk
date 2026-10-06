/**
 * End-to-end check against a running relay.
 *
 * Point it at the relay's own loopback through SSH, so the answer cannot come from
 * some other machine that happens to own the hostname:
 *
 *   ssh -N -L 17421:127.0.0.1:7421 racknerd        # one window
 *   node relay/tools/live-check.mjs                # another
 *
 * Over the internet instead (only meaningful once one machine owns the hostname):
 *
 *   node relay/tools/live-check.mjs --url wss://term.wolfoxlabs.xyz
 *
 * It signs in as this computer's node, mints a real pairing code, pairs a
 * throwaway phone, checks the phone shows up in the computer's list, then lets the
 * phone unbind itself again. Nothing is left behind: no device, no pending code.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const argv = process.argv.slice(2);
const arg = (name, fallback = null) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const url = arg('url', 'ws://127.0.0.1:17421').replace(/\/$/, '');
const configFile = arg('config', path.join(os.homedir(), '.termdesk', 'relay.json'));
let config = {};
try { config = JSON.parse(fs.readFileSync(configFile, 'utf8')); } catch { /* reported below */ }
const nodeId = arg('node', config.nodeId);
const key = arg('key', config.key);

let passes = 0, failures = 0;
const step = (name, ok, detail = '') => {
  if (ok) { passes += 1; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures += 1; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
};

function open(route) {
  const ws = new WebSocket(url + route, { handshakeTimeout: 10_000, maxPayload: 8 * 1024 * 1024 });
  const queue = [], waiters = [];
  ws.on('message', (raw) => {
    let f; try { f = JSON.parse(raw.toString()); } catch { return; }
    const i = waiters.findIndex((w) => w.p(f));
    if (i >= 0) { const w = waiters.splice(i, 1)[0]; clearTimeout(w.timer); w.resolve(f); }
    else queue.push(f);
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
  return { ws, wait, send: (f) => ws.send(JSON.stringify(f)), ready: new Promise((r, j) => { ws.on('open', r); ws.on('error', j); }), closed: new Promise((r) => ws.on('close', (code, reason) => r({ code, reason: String(reason) }))) };
}

if (!nodeId || !key) {
  console.error(`读不到节点凭据：${configFile}（需要 { url, nodeId, key }）`);
  process.exit(2);
}
console.log(`中转 ${url}   节点 ${nodeId}`);

let node = null, phone = null, deviceId = null, token = null;
try {
  node = open('/agent');
  await node.ready;
  node.send({ type: 'relay.auth', nodeId, token: key });
  const ready = await node.wait('relay.ready').catch((err) => err);
  if (ready instanceof Error) {
    const closed = await Promise.race([node.closed, new Promise((r) => setTimeout(() => r(null), 1500))]);
    step('节点已上线', false, closed?.code === 4409 ? '这台电脑的节点已经在线了（代理正在跑），先停掉它再测' : (closed ? `被拒绝：${closed.code} ${closed.reason}` : ready.message));
    throw new Error('stop');
  }
  step('节点已上线', ready.nodeId === nodeId);

  node.send({ type: 'relay.pair.create', requestId: 1, label: '自检手机' });
  const minted = await node.wait('relay.pair.code', (f) => f.requestId === 1).catch((err) => err);
  if (minted instanceof Error || !minted.code) throw new Error(`出码失败：${minted.reason ?? minted.message}`);
  step('电脑能自己要一个配对码', /^[0-9A-Z]{4}(-[0-9A-Z]{4}){2}$/.test(minted.code), minted.code);

  // Typed by hand on purpose: the code has to survive being read off a screen.
  const typed = minted.code.replace(/-/g, '').replace(/0/g, 'O');
  phone = open('/client');
  await phone.ready;
  phone.send({ type: 'auth', token: typed, device: { name: '自检手机' } });
  const paired = await phone.wait('device.paired').catch((err) => err);
  if (paired instanceof Error) throw new Error(`配对失败：${paired.message}`);
  token = paired.token;
  deviceId = paired.deviceId;
  step('手机用码换到了自己的凭据', typeof token === 'string' && token.length >= 32);
  step('手机看到电脑在线', (await phone.wait('node.status')).online === true);

  node.send({ type: 'relay.devices.list', requestId: 2 });
  const list = await node.wait('relay.devices', (f) => f.requestId === 2);
  const mine = list.devices.filter((d) => d.id === deviceId);
  step('电脑的名单里有这台手机，且认得它的名字', mine.length === 1 && mine[0].label === '自检手机', mine[0]?.label ?? '(缺)');

  const reused = open('/client');
  await reused.ready;
  reused.send({ type: 'auth', token: minted.code });
  const reuse = await reused.wait('auth.fail').catch(() => null);
  step('同一个配对码不能用第二次', reuse?.reason === 'invalid credentials');
  reused.ws.close();

  phone.send({ type: 'device.unpair' });
  await phone.wait('device.unpaired');
  step('手机能自己解绑', true);
  await phone.closed;

  const again = open('/client');
  await again.ready;
  again.send({ type: 'auth', token });
  const rejected = await again.wait('auth.fail').catch(() => null);
  step('解绑后凭据立即失效', rejected?.reason === 'invalid credentials');
  again.ws.close();

  node.send({ type: 'relay.devices.list', requestId: 3 });
  const after = await node.wait('relay.devices', (f) => f.requestId === 3);
  step('名单里不再留着这台手机', after.devices.every((d) => d.id !== deviceId));
} catch (err) {
  if (String(err?.message) !== 'stop') { failures += 1; console.log(`  FAIL  ${err?.message ?? err}`); }
} finally {
  phone?.ws.close();
  node?.ws.close();
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
