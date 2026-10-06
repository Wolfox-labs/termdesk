import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createRelay } from '../src/relay.js';
import { digest } from '../src/credentials.js';
import { startRelayConnector } from '../../pc-agent/src/relay-client.js';

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };
function inbox(ws) {
  const messages = [], waiters = [];
  ws.on('message', raw => {
    const f = JSON.parse(raw.toString()), i = waiters.findIndex(w => w.p(f));
    if (i >= 0) { const w = waiters.splice(i, 1)[0]; clearTimeout(w.timer); w.resolve(f); } else messages.push(f);
  });
  return (type, p = () => true) => {
    const predicate = f => f.type === type && p(f), i = messages.findIndex(predicate);
    if (i >= 0) return Promise.resolve(messages.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { p: predicate, resolve, timer: null };
      w.timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error(`Timeout: ${type}`)); }, 5000);
      waiters.push(w);
    });
  };
}
async function connect(url) { const ws = new WebSocket(url); const read = inbox(ws); await once(ws, 'open'); return { ws, read }; }
const send = (ws, f) => ws.send(JSON.stringify(f));
const config = () => ({ version: 1, nodes: [{ id: 'pc', label: 'Test PC', keyHash: digest('node-key') }], devices: [{ id: 'phone', nodeId: 'pc', keyHash: digest('device-key') }], pairings: [{ nodeId: 'pc', codeHash: digest('one-time'), expiresAt: Date.now() + 60_000 }] });
async function fixture(t) {
  const relay = createRelay({ config: config() }), port = await listen(relay.server);
  t.after(() => relay.close()); return { relay, port, url: `ws://127.0.0.1:${port}` };
}

test('rejects client or node credentials before any execution frame', async t => {
  const { url } = await fixture(t);
  for (const [route, frame] of [['/client', { type: 'auth', token: 'wrong' }], ['/agent', { type: 'relay.auth', nodeId: 'pc', token: 'device-key' }]]) {
    const { ws, read } = await connect(url + route); send(ws, frame);
    assert.equal((await read('auth.fail')).reason, 'invalid credentials'); await once(ws, 'close');
  }
});
test('pairs offline once, rotates to device credential, remains authenticated without PC', async t => {
  const { url, relay } = await fixture(t);
  const { ws, read } = await connect(url + '/client'); send(ws, { type: 'auth', token: 'one-time' });
  const paired = await read('device.paired'); assert.ok(paired.token.length >= 32);
  assert.equal((await read('auth.ok')).nodeOnline, false); assert.equal(relay.store.device(paired.token).nodeId, 'pc');
  assert.equal(relay.store.pair('one-time'), null);
  send(ws, { type: 'chat.send', text: 'must not execute' }); assert.equal((await read('error')).code, 'node_offline');
  ws.close(); await once(ws, 'close');
  const second = await connect(url + '/client'); send(second.ws, { type: 'auth', token: paired.token }); await second.read('auth.ok'); second.ws.close();
});
test('node frames cannot target a client assigned to another node', async t => {
  const c = config(); c.nodes.push({ id: 'other', keyHash: digest('other-key') });
  const relay = createRelay({ config: c }), port = await listen(relay.server); t.after(() => relay.close());
  const phone = await connect(`ws://127.0.0.1:${port}/client`); send(phone.ws, { type: 'auth', token: 'device-key' }); await phone.read('auth.ok');
  const node = await connect(`ws://127.0.0.1:${port}/agent`); send(node.ws, { type: 'relay.auth', nodeId: 'other', token: 'other-key' }); await node.read('relay.ready');
  // A spoofed connection id cannot be delivered; then verify the legitimate phone still gets offline rejection.
  send(node.ws, { type: 'relay.frame', connectionId: 'missing', frame: { type: 'chat', id: 'injected' } });
  send(phone.ws, { type: 'ping' }); assert.equal((await phone.read('error')).code, 'node_offline'); phone.ws.close(); node.ws.close();
});
test('reverse connector delivers auth and protocol, streams HTTP upload/download, reports node loss', async t => {
  const { url, port } = await fixture(t);
  const body = Buffer.alloc(1024 * 1024 + 13, 87);
  let uploaded = null;
  const local = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer local-only-token');
    if (req.url.startsWith('/download')) { res.writeHead(200, { 'content-length': body.length, 'content-type': 'application/octet-stream' }); res.end(body); }
    else { const chunks = []; req.on('data', c => chunks.push(c)); req.on('end', () => { uploaded = Buffer.concat(chunks); res.end('{"ok":true}'); }); }
  });
  const wss = new WebSocketServer({ server: local });
  wss.on('connection', ws => ws.on('message', raw => {
    const f = JSON.parse(raw);
    if (f.type === 'auth') { assert.equal(f.token, 'local-only-token'); send(ws, { type: 'auth.ok', hostname: 'local-PC' }); }
    else send(ws, { type: 'pong', t: f.t });
  }));
  const localPort = await listen(local);
  t.after(async () => { for (const ws of wss.clients) ws.terminate(); await new Promise(r => local.close(r)); });
  const phone = await connect(url + '/client'); send(phone.ws, { type: 'auth', token: 'device-key' }); await phone.read('auth.ok');
  const connector = startRelayConnector({ config: { url, nodeId: 'pc', key: 'node-key' }, port: localPort, token: 'local-only-token', log: () => {} });
  t.after(() => connector.stop());
  assert.equal((await phone.read('node.status', f => f.online)).online, true);
  assert.equal((await phone.read('auth.ok')).hostname, 'local-PC');
  send(phone.ws, { type: 'ping', t: 123 }); assert.equal((await phone.read('pong')).t, 123);
  const base = `http://127.0.0.1:${port}`;
  const download = await fetch(base + '/download?path=x', { headers: { Authorization: 'Bearer device-key' } });
  assert.equal(download.status, 200); assert.deepEqual(Buffer.from(await download.arrayBuffer()), body);
  const upload = await fetch(base + '/upload?path=x', { method: 'POST', headers: { Authorization: 'Bearer device-key' }, body });
  assert.equal(upload.status, 200); assert.deepEqual(uploaded, body);
  assert.equal((await fetch(base + '/download')).status, 401);
  connector.stop(); assert.equal((await phone.read('node.status', f => !f.online)).online, false);
  assert.equal((await fetch(base + '/download', { headers: { Authorization: 'Bearer device-key' } })).status, 503);
  phone.ws.close();
});
test('expired pairing code is refused, credential hash is not a credential', async t => {
  const { relay } = await fixture(t); relay.store.config.pairings[0].expiresAt = Date.now() - 1;
  assert.equal(relay.store.pair('one-time'), null);
  assert.equal(relay.store.device(digest('device-key')), undefined);
});

/** A relay with no devices and no codes yet: the state a fresh deployment is in. */
async function bare(t, nodes = [{ id: 'pc', label: 'Test PC', keyHash: digest('node-key') }]) {
  const relay = createRelay({ config: { version: 1, nodes, devices: [], pairings: [] } });
  const port = await listen(relay.server);
  t.after(() => relay.close());
  return { relay, port, url: `ws://127.0.0.1:${port}` };
}
async function asNode(url, id = 'pc', key = 'node-key') {
  const node = await connect(url + '/agent');
  send(node.ws, { type: 'relay.auth', nodeId: id, token: key });
  await node.read('relay.ready');
  return node;
}
/** Pair a phone the way the app does, and return the credential it keeps. */
async function pairPhone(url, node, name) {
  send(node.ws, { type: 'relay.pair.create', label: name });
  const minted = await node.read('relay.pair.code');
  const phone = await connect(url + '/client');
  send(phone.ws, { type: 'auth', token: minted.code, device: { name } });
  const token = (await phone.read('device.paired')).token;
  // The relay never answers a client's auth itself: the computer's agent does,
  // so being attached to an online node is what "connected" looks like here.
  assert.equal((await phone.read('node.status')).online, true);
  phone.ws.close(); await once(phone.ws, 'close');
  return { token, minted };
}

test('a computer mints its own pairing code, and the phone it pairs shows up in its list', async t => {
  const { url, relay } = await bare(t);
  const node = await asNode(url);
  send(node.ws, { type: 'relay.pair.create', requestId: 7, label: '备用手机' });
  const minted = await node.read('relay.pair.code');
  assert.equal(minted.requestId, 7);
  assert.match(minted.code, /^[0-9A-Z]{4}(-[0-9A-Z]{4}){2}$/);
  assert.ok(minted.expiresAt > Date.now() && minted.expiresAt <= Date.now() + 60 * 60_000);
  assert.equal(relay.store.config.pairings.length, 1);
  assert.ok(!JSON.stringify(relay.store.config.pairings).includes(minted.code.replace(/-/g, '')), 'only the hash is written down');

  // Typed by hand: grouping dropped, and an O where the code has a zero.
  const phone = await connect(url + '/client');
  send(phone.ws, { type: 'auth', token: minted.code.replace(/-/g, '').replace(/0/g, 'O'), device: { name: '小米 15' } });
  assert.ok((await phone.read('device.paired')).token.length >= 32);
  assert.equal((await phone.read('node.status')).online, true);
  await node.read('relay.devices.changed');

  send(node.ws, { type: 'relay.devices.list', requestId: 8 });
  const list = await node.read('relay.devices');
  assert.equal(list.devices.length, 1);
  assert.equal(list.devices[0].label, '小米 15');
  assert.ok(list.devices[0].lastSeenAt);
  // One-time: the same code cannot mint a second device.
  assert.equal(relay.store.pair(minted.code), null);
  phone.ws.close(); node.ws.close();
});

test('a computer cannot stockpile pairing codes', async t => {
  const { url } = await bare(t);
  const node = await asNode(url);
  for (let i = 0; i < 5; i += 1) { send(node.ws, { type: 'relay.pair.create' }); assert.ok((await node.read('relay.pair.code')).code); }
  send(node.ws, { type: 'relay.pair.create' });
  assert.equal((await node.read('relay.pair.denied')).reason, '待用配对码已达上限');
  node.ws.close();
});

test('revoking one phone leaves the others working, and another node cannot revoke it', async t => {
  const { url, relay } = await bare(t, [{ id: 'pc', keyHash: digest('node-key') }, { id: 'other', keyHash: digest('other-key') }]);
  const node = await asNode(url), other = await asNode(url, 'other', 'other-key');
  const first = await pairPhone(url, node, 'A'), second = await pairPhone(url, node, 'B');
  const [firstDevice] = relay.store.devicesOf('pc');

  send(other.ws, { type: 'relay.devices.revoke', deviceId: firstDevice.id });
  assert.deepEqual((await other.read('relay.devices')).devices, []);
  assert.equal(relay.store.devicesOf('pc').filter(d => d.revoked).length, 0);

  send(node.ws, { type: 'relay.devices.revoke', deviceId: firstDevice.id });
  const after = await node.read('relay.devices');
  assert.equal(after.devices.length, 2);
  assert.deepEqual(after.devices.filter(d => d.revoked).map(d => d.id), [firstDevice.id]);

  const revoked = await connect(url + '/client');
  send(revoked.ws, { type: 'auth', token: first.token });
  assert.equal((await revoked.read('auth.fail')).reason, 'invalid credentials');
  const survivor = await connect(url + '/client');
  send(survivor.ws, { type: 'auth', token: second.token });
  assert.equal((await survivor.read('node.status')).online, true);
  survivor.ws.close(); node.ws.close(); other.ws.close();
});

test('a phone can unbind itself: the credential is gone, not just forgotten', async t => {
  const { url, relay } = await bare(t);
  const node = await asNode(url);
  send(node.ws, { type: 'relay.pair.create' });
  const minted = await node.read('relay.pair.code');
  const phone = await connect(url + '/client');
  send(phone.ws, { type: 'auth', token: minted.code, device: { name: '旧手机' } });
  const token = (await phone.read('device.paired')).token;
  assert.equal((await phone.read('node.status')).online, true);

  send(phone.ws, { type: 'device.unpair' });
  await phone.read('device.unpaired');
  await once(phone.ws, 'close');
  assert.deepEqual(relay.store.devicesOf('pc'), []);
  await node.read('relay.devices.changed');

  const again = await connect(url + '/client');
  send(again.ws, { type: 'auth', token });
  assert.equal((await again.read('auth.fail')).reason, 'invalid credentials');
  node.ws.close();
});

test('codes expire, and an unauthenticated caller cannot mint one', async t => {
  const { url, relay } = await bare(t);
  const node = await asNode(url);
  send(node.ws, { type: 'relay.pair.create', ttlMs: 60_000 });
  const minted = await node.read('relay.pair.code');
  relay.store.config.pairings[0].expiresAt = Date.now() - 1;

  const stranger = await connect(url + '/client');
  send(stranger.ws, { type: 'relay.pair.create' });
  assert.equal((await stranger.read('auth.fail')).reason, 'invalid credentials');
  assert.equal(relay.store.config.pairings.filter(p => p.expiresAt > Date.now()).length, 0);

  const late = await connect(url + '/client');
  send(late.ws, { type: 'auth', token: minted.code });
  assert.equal((await late.read('auth.fail')).reason, 'invalid credentials');
  node.ws.close();
});

test('the agent-side connector mints, lists and revokes over its own channel', async t => {
  // This is the path the PC agent actually uses: nothing here talks to the relay
  // as anything other than the node itself.
  const { url } = await bare(t);
  const local = http.createServer(() => {});
  const wss = new WebSocketServer({ server: local });
  wss.on('connection', ws => ws.on('message', raw => {
    const f = JSON.parse(raw);
    if (f.type === 'auth') send(ws, { type: 'auth.ok', hostname: 'local-PC' });
  }));
  const localPort = await listen(local);
  t.after(async () => { for (const ws of wss.clients) ws.terminate(); await new Promise(r => local.close(r)); });

  let markReady; const ready = new Promise(resolve => { markReady = resolve; });
  const connector = startRelayConnector({
    config: { url, nodeId: 'pc', key: 'node-key' }, port: localPort, token: 'local-only-token', log: () => {}, onReady: () => markReady(),
  });
  t.after(() => connector.stop());
  await ready;

  const { code, expiresAt } = await connector.pairingCode({ label: 'yaosw' });
  assert.match(code, /^[0-9A-Z]{4}(-[0-9A-Z]{4}){2}$/);
  assert.ok(expiresAt > Date.now());

  const phone = await connect(url + '/client');
  send(phone.ws, { type: 'auth', token: code, device: { name: '小米 15' } });
  const token = (await phone.read('device.paired')).token;
  assert.equal((await phone.read('node.status')).online, true);

  const listed = await connector.devices();
  assert.equal(listed.length, 1);
  assert.equal(listed[0].label, '小米 15');
  const after = await connector.revoke(listed[0].id);
  assert.deepEqual(after.filter(d => d.revoked).map(d => d.id), [listed[0].id]);

  phone.ws.close(); await once(phone.ws, 'close');
  const rejected = await connect(url + '/client');
  send(rejected.ws, { type: 'auth', token });
  assert.equal((await rejected.read('auth.fail')).reason, 'invalid credentials');
  assert.deepEqual(await connector.devices().then(d => d.filter(x => !x.revoked)), []);
});
