/**
 * Prove the remote browser surface is actually usable, not just reachable.
 *
 * dsh-web-check.js confirms the mux upgrades. That is necessary but not
 * sufficient: the page could still fail to boot, or the mux could accept the
 * socket without answering any RPC. This drives the same remote surface the
 * browser drives, over the public tunnel, and asserts that the host answers a
 * real call 鈥?which is what "I can talk to my machine from my phone" means.
 *
 * Usage: node tools/dsh-remote-check.js [hostname]
 */

import { WebSocket } from 'ws';

const host = process.env.TERMDESK_DSH_HOST || process.argv[2] || '';
if (!host) {
  console.error('usage: set TERMDESK_DSH_HOST or pass the hostname, e.g. dsh.example.com');
  process.exit(2);
}
const base = `https://${host}`;

let passes = 0;
let failures = 0;
const check = (name, ok, detail) => {
  if (ok) { passes++; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures++; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
};

/** Collect frames for `ms` and return them. */
function collect(ws, ms) {
  const seen = [];
  return new Promise((resolve) => {
    const onMsg = (raw) => {
      try { seen.push(JSON.parse(raw.toString())); } catch { seen.push({ raw: raw.toString().slice(0, 120) }); }
    };
    ws.on('message', onMsg);
    setTimeout(() => {
      ws.off('message', onMsg);
      resolve(seen);
    }, ms);
  });
}

(async () => {
  console.log(`remote browser surface: ${base}`);
  console.log('');

  // The page must boot, which means the document AND the boot payload.
  let html = '';
  try {
    const res = await fetch(base, { signal: AbortSignal.timeout(30000) });
    html = await res.text();
    check('page loads over the public tunnel', res.status === 200, `${res.status}, ${html.length}B`);
  } catch (err) {
    check('page loads over the public tunnel', false, err.message);
    finish();
    return;
  }

  // The SPA is not standalone: without the injected boot payload it cannot start.
  const boot = html.match(/__DSH_BOOT__/);
  check('document carries the boot payload the SPA needs', Boolean(boot));

  // Now the real dependency: the mux, spoken to like the browser speaks to it.
  const ws = new WebSocket(`wss://${host}/api/remote.mux`, {
    handshakeTimeout: 25000,
    // The browser surface speaks its own subprotocol; sending the header is
    // closer to what the page does than a bare upgrade.
    headers: { Origin: base },
  });

  const opened = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 30000);
    ws.on('open', () => { clearTimeout(timer); resolve(true); });
    ws.on('unexpected-response', (_q, res) => { clearTimeout(timer); resolve(`HTTP ${res.statusCode}`); });
    ws.on('error', (e) => { clearTimeout(timer); resolve(`error: ${e.message}`); });
  });
  check('mux accepts a browser-style upgrade (with Origin)', opened === true,
    opened === true ? 'upgraded' : String(opened));

  if (opened !== true) {
    finish();
    return;
  }

  // Does the host actually speak on this socket, or is it a silent accept?
  const frames = await collect(ws, 12000);
  check('the host sends data on the mux without being asked', frames.length > 0,
    `${frames.length} frame(s): ${frames.slice(0, 2).map((f) => f.type ?? f.raw ?? '?').join(', ')}`);

  // This is the decisive one: an RPC must come back. A socket that opens but
  // answers nothing is exactly the "page loads, nothing works" failure mode.
  const probe = { jsonrpc: '2.0', id: 'probe-1', method: 'session.list', params: {} };
  ws.send(JSON.stringify(probe));
  const after = await collect(ws, 15000);

  const answered = after.find((f) => f.id === 'probe-1');
  const anyResponse = after.filter((f) => f.id !== undefined);
  check('the host answers an RPC over the public tunnel', Boolean(answered),
    answered ? JSON.stringify(answered).slice(0, 160)
      : `${after.length} frame(s), ${anyResponse.length} with an id`);

  if (!answered && after.length > 0) {
    console.log('');
    console.log('  frames seen after the probe (helps identify the right framing):');
    for (const f of after.slice(0, 6)) console.log('    ' + JSON.stringify(f).slice(0, 200));
  }

  try { ws.close(); } catch { /* ignore */ }
  finish();
})().catch((err) => {
  check('check completed without an unexpected throw', false, err?.message ?? String(err));
  finish();
});

function finish() {
  setTimeout(() => {
    console.log('');
    console.log(`${passes} passed, ${failures} failed`);
    process.exit(failures === 0 ? 0 : 1);
  }, 300);
}
