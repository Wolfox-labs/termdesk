/**
 * Speak the Remote stream mux protocol to the host, over the public tunnel.
 *
 * The mux is not JSON-RPC. Its wire format (from
 * @deepseek-ai/dsh-api-gateway stream-protocol) is:
 *
 *   client -> host : { type:'open', streamId, endpoint, payload }
 *                    { type:'cancel', streamId }
 *   host -> client : { type:'item', streamId, value? }
 *                    { type:'end', streamId }
 *                    { type:'error', streamId, error }
 *
 * Opening the forwarded-event stream (`$events`) is expected to answer with a
 * `ready` item, which proves the host is serving this origin end to end.
 *
 * Usage: node tools/mux-probe.js [hostname]
 */

import { WebSocket } from 'ws';

const host = process.env.TERMDESK_DSH_HOST || process.argv[2] || '';
if (!host) {
  console.error('usage: set TERMDESK_DSH_HOST or pass the hostname, e.g. dsh.example.com');
  process.exit(2);
}
const showAll = process.argv.includes('--verbose');

let passes = 0;
let failures = 0;
const check = (name, ok, detail) => {
  if (ok) { passes++; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures++; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
};

function collect(ws, ms) {
  const seen = [];
  return new Promise((resolve) => {
    const onMsg = (raw) => {
      let v;
      try { v = JSON.parse(raw.toString()); } catch { v = { unparsed: raw.toString().slice(0, 120) }; }
      seen.push(v);
      if (showAll) console.log(`      <- ${JSON.stringify(v).slice(0, 220)}`);
    };
    ws.on('message', onMsg);
    setTimeout(() => { ws.off('message', onMsg); resolve(seen); }, ms);
  });
}

(async () => {
  console.log(`mux: wss://${host}/api/remote.mux`);
  console.log('');

  const ws = new WebSocket(`wss://${host}/api/remote.mux`, {
    handshakeTimeout: 25000,
    headers: { Origin: `https://${host}` },
  });

  const opened = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve('timeout'), 30000);
    ws.on('open', () => { clearTimeout(timer); resolve(true); });
    ws.on('unexpected-response', (_q, res) => { clearTimeout(timer); resolve(`HTTP ${res.statusCode}`); });
    ws.on('error', (e) => { clearTimeout(timer); resolve(`error: ${e.message}`); });
  });
  check('mux upgrades through the tunnel', opened === true, opened === true ? 'upgraded' : String(opened));
  if (opened !== true) { finish(); return; }

  // 1. open the forwarded-event stream; the host answers `ready`.
  ws.send(JSON.stringify({
    type: 'open',
    streamId: 's-events',
    endpoint: '$events',
    payload: { args: {} },
  }));
  const eventsFrames = await collect(ws, 10000);
  const ready = eventsFrames.find((f) => f.type === 'item' && f.value?.type === 'ready');
  check('opening $events returns the host "ready" item', Boolean(ready),
    ready ? 'ready received' : `${eventsFrames.length} frame(s): ${eventsFrames.map((f) => f.type).join(',') || 'none'}`);
  if (eventsFrames.length) {
    console.log(`      frames: ${JSON.stringify(eventsFrames.slice(0, 3)).slice(0, 260)}`);
  }

  // 2. a unary Remote call through the same mux. The endpoint name is the
  //    Typert Remote export; a wrong name must still produce a structured
  //    `error` frame rather than silence, and that distinction is the point:
  //    silence would mean the tunnel or the fence is eating the request.
  const candidates = [
    { streamId: 's-list', endpoint: 'session.list', payload: {} },
    { streamId: 's-ws', endpoint: 'workspace.list', payload: {} },
    { streamId: 's-meta', endpoint: 'meta.list', payload: {} },
  ];
  for (const c of candidates) {
    ws.send(JSON.stringify({ type: 'open', ...c }));
  }
  const rpcFrames = await collect(ws, 12000);
  const answered = rpcFrames.filter((f) => f.type === 'item' || f.type === 'end' || f.type === 'error');
  check('the host answers stream requests (item/end/error, not silence)', answered.length > 0,
    answered.length > 0
      ? answered.map((f) => `${f.streamId}:${f.type}`).join(' ')
      : `${rpcFrames.length} frame(s), none matched`);

  const errors = rpcFrames.filter((f) => f.type === 'error');
  if (errors.length > 0) {
    console.log('');
    console.log('      first error frame (tells us the correct endpoint naming):');
    console.log('      ' + JSON.stringify(errors[0]).slice(0, 300));
  }

  try { ws.close(); } catch { /* ignore */ }
  finish();
})().catch((err) => {
  check('probe completed without an unexpected throw', false, err?.message ?? String(err));
  finish();
});

function finish() {
  setTimeout(() => {
    console.log('');
    console.log(`${passes} passed, ${failures} failed`);
    process.exit(failures === 0 ? 0 : 1);
  }, 300);
}
