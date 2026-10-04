/** Reverse transport only. Execution/authentication stays inside the local agent. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { WebSocket } from 'ws';
import { SocksProxyAgent } from 'socks-proxy-agent';

const MAX_QUEUE = 16 * 1024 * 1024;
export function loadRelayConfig() {
  const file = process.env.TERMDESK_RELAY_CONFIG || path.join(os.homedir(), '.termdesk', 'relay.json');
  if (!fs.existsSync(file)) return null;
  const config = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!config.url?.startsWith('wss://') || !config.nodeId || !config.key) throw new Error('Invalid relay config: secure URL, nodeId and key required');
  return config;
}
function send(ws, value) {
  if (ws.readyState !== WebSocket.OPEN) return false;
  if (ws.bufferedAmount > MAX_QUEUE) { ws.terminate(); return false; }
  ws.send(JSON.stringify(value)); return true;
}
export function startRelayConnector({ config, port, token, accessKey = '', log = console.log }) {
  let control = null, retry = null, heartbeat = null, stopped = false, failures = 0;
  const localSockets = new Map(), transfers = new Set();
  const proxy = config.proxy ? new SocksProxyAgent(config.proxy) : undefined;
  const endpoint = route => { const url = new URL(config.url); url.pathname = route; url.search = ''; return url; };
  const localBase = `http://127.0.0.1:${port}`;
  function dropLocals() {
    for (const ws of localSockets.values()) ws.terminate(); localSockets.clear();
    for (const ws of transfers) ws.terminate(); transfers.clear();
  }
  function connect() {
    if (stopped) return;
    const ws = new WebSocket(endpoint('/agent'), { agent: proxy, maxPayload: 8 * 1024 * 1024, handshakeTimeout: 15_000 });
    control = ws; let alive = true;
    ws.on('open', () => send(ws, { type: 'relay.auth', nodeId: config.nodeId, token: config.key }));
    ws.on('pong', () => { alive = true; });
    heartbeat = setInterval(() => { if (!alive) ws.terminate(); else if (ws.readyState === WebSocket.OPEN) { alive = false; ws.ping(); } }, 20_000);
    ws.on('message', raw => {
      let f; try { f = JSON.parse(raw.toString()); } catch { ws.terminate(); return; }
      if (f.type === 'relay.ready') { failures = 0; log('[termdesk] VPS node connected'); }
      else if (f.type === 'relay.open' && typeof f.connectionId === 'string') openLocal(f.connectionId, ws);
      else if (f.type === 'relay.close') { localSockets.get(f.connectionId)?.terminate(); localSockets.delete(f.connectionId); }
      else if (f.type === 'relay.frame') {
        const local = localSockets.get(f.connectionId);
        if (local && f.frame?.type !== 'auth') send(local, f.frame);
      } else if (f.type === 'relay.http.open' && typeof f.ticket === 'string') openTransfer(f.ticket);
    });
    // Do not echo errors that could contain credentials/proxy URLs.
    ws.on('error', () => log('[termdesk] VPS transport unavailable; retrying'));
    ws.on('close', (code) => {
      clearInterval(heartbeat); dropLocals();
      if (stopped) return;
      failures += 1;
      const ms = Math.min(30_000, 1000 * 2 ** Math.min(failures, 5));
      log(`[termdesk] VPS transport closed (${code}); reconnect in ${ms / 1000}s`);
      retry = setTimeout(connect, ms);
    });
  }
  function openLocal(id, owner) {
    if (localSockets.has(id)) return;
    const url = new URL(localBase); url.protocol = 'ws:';
    if (accessKey) url.searchParams.set('access', accessKey);
    const local = new WebSocket(url, { maxPayload: 8 * 1024 * 1024 });
    localSockets.set(id, local);
    local.on('open', () => send(local, { type: 'auth', token }));
    local.on('message', raw => {
      try { send(owner, { type: 'relay.frame', connectionId: id, frame: JSON.parse(raw.toString()) }); }
      catch { local.terminate(); }
    });
    local.on('error', () => {});
    local.on('close', () => {
      if (localSockets.get(id) !== local) return;
      localSockets.delete(id);
      // Re-register the node after an unexpected local failure; phone auth stays at the VPS.
      if (owner.readyState === WebSocket.OPEN) owner.terminate();
    });
  }
  function openTransfer(ticket) {
    if (transfers.size >= 8) return;
    const ws = new WebSocket(endpoint('/transfer'), { agent: proxy, headers: { Authorization: `Bearer ${config.key}`, 'X-TermDesk-Ticket': ticket }, maxPayload: 8 * 1024 * 1024 });
    transfers.add(ws);
    let req = null, response = null;
    ws.on('error', () => {});
    ws.on('message', (raw, binary) => {
      if (binary) {
        if (!req) { ws.terminate(); return; }
        if (!req.write(raw)) { ws.pause(); req.once('drain', () => ws.resume()); }
        return;
      }
      let f; try { f = JSON.parse(raw.toString()); } catch { ws.terminate(); return; }
      if (f.type === 'http.request' && !req) {
        const url = new URL(f.path, localBase);
        const allowed = new Set(['/download', '/upload', '/upload/session', '/upload/session/commit']);
        if (url.origin !== localBase || !allowed.has(url.pathname) || !['GET', 'POST', 'PUT', 'DELETE'].includes(f.method)) { ws.terminate(); return; }
        const headers = { Authorization: `Bearer ${token}`, 'content-type': f.contentType || 'application/octet-stream' };
        if (accessKey) headers['X-TermDesk-Key'] = accessKey;
        if (/^\d+$/.test(String(f.contentLength))) headers['content-length'] = String(f.contentLength);
        req = http.request(url, { method: f.method, headers }, res => {
          response = res;
          send(ws, { type: 'http.response', status: res.statusCode, headers: res.headers });
          res.on('data', chunk => {
            res.pause();
            if (ws.readyState !== WebSocket.OPEN) { res.destroy(); return; }
            ws.send(chunk, { binary: true }, err => { if (err) ws.terminate(); else res.resume(); });
          });
          res.on('end', () => send(ws, { type: 'http.response.end' }));
          res.on('error', () => ws.terminate());
        });
        req.on('error', () => { send(ws, { type: 'http.error' }); ws.terminate(); });
        req.setTimeout(10 * 60_000, () => ws.terminate());
      } else if (f.type === 'http.request.end') req?.end();
      else ws.terminate();
    });
    ws.on('close', () => { transfers.delete(ws); req?.destroy(); response?.destroy(); });
  }
  connect();
  return { stop() { stopped = true; clearTimeout(retry); clearInterval(heartbeat); dropLocals(); control?.terminate(); } };
}
