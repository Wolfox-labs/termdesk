import http from 'node:http';
import crypto from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { CredentialStore, secret } from './credentials.js';

const MAX_FRAME = 8 * 1024 * 1024;
const TRANSFERS = new Set(['/download', '/upload', '/upload/session', '/upload/session/commit']);
const send = (ws, frame) => {
  if (ws?.readyState !== WebSocket.OPEN) return false;
  if (ws.bufferedAmount > 16 * 1024 * 1024) { ws.close(4413, 'slow consumer'); return false; }
  ws.send(JSON.stringify(frame)); return true;
};
const parse = raw => { try { const f = JSON.parse(raw.toString()); return f && typeof f.type === 'string' ? f : null; } catch { return null; } };

export function createRelay({ config, configFile = null }) {
  const store = new CredentialStore(config, configFile);
  const nodes = new Map(), clients = new Map(), tickets = new Map(), transfers = new Set();
  const attempts = new Map();
  const nodeInfo = id => config.nodes.find(n => n.id === id);
  function nodeStatus(client, online) {
    send(client.ws, { type: 'node.status', online, nodeId: client.nodeId, hostname: nodeInfo(client.nodeId)?.label || client.nodeId });
  }
  function openClient(client, node) {
    client.attached = true;
    send(node, { type: 'relay.open', connectionId: client.id });
  }
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://relay.local');
    res.setHeader('cache-control', 'no-store');
    if (url.pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, service: 'termdesk-relay', protocol: 1 })); return;
    }
    if (!TRANSFERS.has(url.pathname)) { res.writeHead(404); res.end(); return; }
    const device = store.device(/^Bearer (.+)$/i.exec(req.headers.authorization || '')?.[1]);
    if (!device) { res.writeHead(401); res.end(); return; }
    const node = nodes.get(device.nodeId);
    if (!node) { res.writeHead(503, { 'content-type': 'application/json' }); res.end('{"code":"node_offline"}'); return; }
    if (tickets.size + transfers.size >= 8) { res.writeHead(429); res.end(); return; }
    req.pause();
    const ticket = secret();
    const timer = setTimeout(() => {
      tickets.delete(ticket); if (!res.writableEnded) { res.writeHead(504); res.end(); }
    }, 20_000);
    tickets.set(ticket, { req, res, nodeId: device.nodeId, timer });
    res.on('close', () => { const t = tickets.get(ticket); if (t) { clearTimeout(t.timer); tickets.delete(ticket); } });
    send(node, { type: 'relay.http.open', ticket });
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://relay.local');
    if (url.pathname === '/transfer') {
      const ticket = req.headers['x-termdesk-ticket'];
      const t = tickets.get(ticket);
      const token = /^Bearer (.+)$/i.exec(req.headers.authorization || '')?.[1];
      if (!t || !store.node(t.nodeId, token) || !nodes.has(t.nodeId)) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return; }
      tickets.delete(ticket); clearTimeout(t.timer);
      wss.handleUpgrade(req, socket, head, ws => bridgeHttp(ws, t)); return;
    }
    if (!['/agent', '/client', '/'].includes(url.pathname)) { socket.end('HTTP/1.1 404 Not Found\r\n\r\n'); return; }
    // cloudflared is the only inbound proxy; use its authenticated edge IP header for throttling.
    const peer = String(req.headers['cf-connecting-ip'] || req.socket.remoteAddress);
    const rec = attempts.get(peer);
    if (rec?.until > Date.now() && rec.count >= 8) { socket.end('HTTP/1.1 429 Too Many Requests\r\n\r\n'); return; }
    wss.handleUpgrade(req, socket, head, ws => accept(ws, url.pathname, peer));
  });
  function accept(ws, route, peer) {
    let identity = null;
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    ws.on('error', () => {});
    const timeout = setTimeout(() => ws.close(4401, 'authentication timeout'), 10_000);
    const reject = () => {
      const prev = attempts.get(peer);
      attempts.set(peer, { count: prev?.until > Date.now() ? prev.count + 1 : 1, until: Date.now() + 30_000 });
      send(ws, { type: 'auth.fail', reason: 'invalid credentials' }); ws.close(4401, 'unauthorized');
    };
    ws.on('message', (raw, binary) => {
      const f = !binary && parse(raw);
      if (!f) { ws.close(4400, 'invalid frame'); return; }
      if (!identity) {
        if (route === '/agent') {
          const node = f.type === 'relay.auth' && store.node(f.nodeId, f.token);
          if (!node) { reject(); return; }
          if (nodes.has(node.id)) { ws.close(4409, 'node already connected'); return; }
          identity = { role: 'node', id: node.id }; nodes.set(node.id, ws);
          clearTimeout(timeout); attempts.delete(peer);
          send(ws, { type: 'relay.ready', nodeId: node.id });
          for (const c of clients.values()) if (c.nodeId === node.id) { nodeStatus(c, true); openClient(c, ws); }
        } else {
          if (f.type !== 'auth') { reject(); return; }
          let device = store.device(f.token), paired = null;
          if (!device) {
            try { paired = store.pair(f.token); device = paired?.device; }
            catch { ws.close(1011, 'credential storage unavailable'); return; }
          }
          if (!device || !nodeInfo(device.nodeId)) { reject(); return; }
          // The execution agent has one event route. Be explicit instead of letting clients steal it silently.
          if ([...clients.values()].some(c => c.nodeId === device.nodeId)) { ws.close(4409, 'another client is active'); return; }
          const c = { id: crypto.randomUUID(), ws, nodeId: device.nodeId, deviceId: device.id, attached: false };
          identity = { role: 'client', id: c.id }; clients.set(c.id, c);
          clearTimeout(timeout); attempts.delete(peer);
          if (paired) send(ws, { type: 'device.paired', token: paired.token, deviceId: device.id });
          const node = nodes.get(c.nodeId);
          nodeStatus(c, Boolean(node));
          if (node) openClient(c, node);
          else send(ws, { type: 'auth.ok', relay: true, nodeOnline: false, hostname: nodeInfo(c.nodeId).label || c.nodeId });
        }
        return;
      }
      if (identity.role === 'node') {
        const c = clients.get(f.connectionId);
        if (!c || c.nodeId !== identity.id) return;
        if (f.type === 'relay.frame' && f.frame && typeof f.frame.type === 'string') send(c.ws, f.frame);
        if (f.type === 'relay.closed') { c.attached = false; nodeStatus(c, false); }
      } else {
        const c = clients.get(identity.id), node = nodes.get(c.nodeId);
        if (f.type === 'auth') { ws.close(4400, 'already authenticated'); return; }
        if (node && c.attached) send(node, { type: 'relay.frame', connectionId: c.id, frame: f });
        else send(ws, { type: 'error', code: 'node_offline', message: '电脑内核离线，客户端仍可查看已缓存内容；内核上线后自动恢复' });
      }
    });
    ws.on('close', () => {
      clearTimeout(timeout);
      if (!identity) return;
      if (identity.role === 'node' && nodes.get(identity.id) === ws) {
        nodes.delete(identity.id);
        for (const c of clients.values()) if (c.nodeId === identity.id) { c.attached = false; nodeStatus(c, false); }
        for (const [ticket, t] of tickets) if (t.nodeId === identity.id) { clearTimeout(t.timer); tickets.delete(ticket); t.res.writeHead(503); t.res.end(); }
        for (const transfer of transfers) if (transfer.nodeId === identity.id) transfer.ws.terminate();
      } else if (identity.role === 'client') {
        const c = clients.get(identity.id); clients.delete(identity.id);
        if (c) send(nodes.get(c.nodeId), { type: 'relay.close', connectionId: c.id });
      }
    });
  }
  function bridgeHttp(ws, { req, res, nodeId }) {
    const record = { ws, nodeId }; transfers.add(record);
    let headers = false, done = false, requestBytes = 0;
    const timer = setTimeout(() => ws.terminate(), 10 * 60_000);
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    ws.on('error', () => {});
    send(ws, { type: 'http.request', method: req.method, path: req.url, contentType: req.headers['content-type'] || 'application/octet-stream', contentLength: req.headers['content-length'] });
    req.on('data', chunk => {
      requestBytes += chunk.length;
      if (requestBytes > 40 * 1024 * 1024) { ws.terminate(); return; }
      req.pause();
      ws.send(chunk, { binary: true }, err => { if (err) ws.terminate(); else req.resume(); });
    });
    req.on('end', () => send(ws, { type: 'http.request.end' }));
    req.on('error', () => ws.terminate());
    res.on('close', () => { if (!done) ws.terminate(); });
    ws.on('message', (raw, binary) => {
      if (binary) {
        if (!headers || done) { ws.terminate(); return; }
        if (!res.write(raw)) { ws.pause(); res.once('drain', () => ws.resume()); }
        return;
      }
      const f = parse(raw);
      if (f?.type === 'http.response' && !headers && Number.isInteger(f.status) && f.status >= 100 && f.status <= 599) {
        headers = true;
        const safe = {};
        for (const name of ['content-type', 'content-length', 'content-disposition']) if (typeof f.headers?.[name] === 'string') safe[name] = f.headers[name];
        res.writeHead(f.status, safe);
      } else if (f?.type === 'http.response.end' && headers) { done = true; res.end(); ws.close(1000); }
      else if (f?.type === 'http.error') ws.terminate();
    });
    ws.on('close', () => {
      clearTimeout(timer); transfers.delete(record); if (!req.complete) req.destroy();
      if (!done) { if (!res.headersSent) res.writeHead(502); res.end(); }
    });
    req.resume();
  }
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) { if (ws.alive === false) ws.terminate(); else { ws.alive = false; ws.ping(); } }
    for (const [peer, rec] of attempts) if (rec.until < Date.now()) attempts.delete(peer);
  }, 20_000);
  heartbeat.unref();
  return {
    server, store,
    async close() {
      clearInterval(heartbeat);
      for (const t of tickets.values()) { clearTimeout(t.timer); t.res.destroy(); }
      tickets.clear(); for (const ws of wss.clients) ws.terminate();
      await new Promise(resolve => server.close(resolve));
    },
  };
}
