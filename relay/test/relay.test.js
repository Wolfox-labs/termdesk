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
