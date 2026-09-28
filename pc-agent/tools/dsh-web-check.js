/**
 * Verify the DSH Web UI is actually usable through the tunnel, not merely
 * that its HTML loads.
 *
 * The desktop app's backend is not a plain document server: the UI drives it
 * over a WebSocket mux (see the api-gateway in the DSH checkout, which owns
 * `/api/remote.mux`). A 200 on `/` therefore proves almost nothing. This checks
 * the real dependency chain:
 *
 *   1. the SPA document loads and carries the boot payload it needs
 *   2. static assets referenced by the document are fetchable
 *   3. the WebSocket mux completes its upgrade through Cloudflare
 *
 * Usage: node tools/dsh-web-check.js [hostname]
 */

import { WebSocket } from 'ws';

const host = process.argv[2] || 'dsh.example.com';
const base = `https://${host}`;

let passes = 0;
let failures = 0;
const check = (name, ok, detail) => {
  if (ok) { passes++; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures++; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
};

/** Try a list of candidate mux paths; the first that upgrades wins. */
function tryUpgrade(pathname, timeoutMs = 20000) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (result) => {
      if (settled) return;
      settled = true;
      try { ws.close(); } catch { /* ignore */ }
      resolve(result);
    };
    const ws = new WebSocket(`wss://${host}${pathname}`, { handshakeTimeout: timeoutMs });
    const timer = setTimeout(() => done({ ok: false, why: 'timeout' }), timeoutMs + 2000);
    ws.on('open', () => { clearTimeout(timer); done({ ok: true, status: 101, got: 'open' }); });
    ws.on('message', () => { clearTimeout(timer); done({ ok: true, status: 101, got: 'message' }); });
    ws.on('unexpected-response', (_q, res) => { clearTimeout(timer); done({ ok: false, status: res.statusCode }); });
    ws.on('error', (e) => { clearTimeout(timer); done({ ok: false, why: e.message }); });
  });
}

(async () => {
  console.log(`dsh web through tunnel: ${base}`);
  console.log('');

  // 1. the document
  let html = '';
  try {
    const res = await fetch(base, { signal: AbortSignal.timeout(30000) });
    html = await res.text();
    check('SPA document loads over the tunnel', res.status === 200 && html.length > 1000,
      `${res.status}, ${html.length} bytes`);
  } catch (err) {
    check('SPA document loads over the tunnel', false, err.message);
    finish();
    return;
  }

  // 2. the boot payload the shell needs. apps/web is not standalone: only the
  //    host injects this, so its absence means the page cannot boot.
  const boot = html.match(/__DSH_BOOT__|window\.__DSH/i);
  check('document carries the host-injected boot payload', Boolean(boot), boot ? boot[0] : 'not found');

  // 3. assets the document references
  const assetPaths = [...html.matchAll(/(?:src|href)="(\/[^"]+\.(?:js|css))"/g)].map((m) => m[1]);
  const unique = [...new Set(assetPaths)].slice(0, 4);
  let assetsOk = 0;
  for (const p of unique) {
    try {
      const r = await fetch(base + p, { signal: AbortSignal.timeout(25000) });
      if (r.status === 200) assetsOk++;
    } catch { /* counted below */ }
  }
  check('static assets load over the tunnel', unique.length === 0 || assetsOk === unique.length,
    `${assetsOk}/${unique.length} of ${unique.join(', ')}`);

  // 4. the WebSocket mux the UI actually runs on
  const mux = await tryUpgrade('/api/remote.mux');
  check('the remote WebSocket mux upgrades through Cloudflare', mux.ok === true,
    mux.ok ? 'upgraded' : `status=${mux.status ?? '-'} why=${mux.why ?? '-'}`);

  if (!mux.ok) {
    console.log('');
    console.log('  (a rejected upgrade can also mean the mux wants a subprotocol or');
    console.log('   query parameters; inspect the browser network tab to confirm)');
  }

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
