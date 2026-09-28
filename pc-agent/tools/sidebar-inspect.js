/**
 * Inspect the DSH sidebar at phone width, in a real browser, without a
 * headless-automation dependency.
 *
 * The user reports that once the left sidebar is open on their phone there is
 * no way to close it. Reading minified CSS suggested a toggle button exists in
 * the sidebar's logo row, but reasoning about bundled CSS is not evidence: the
 * button could be unmounted, off-screen, or covered. This drives Chrome over
 * CDP at a phone-sized viewport and measures what is actually rendered.
 *
 * Read-only: it loads the page and evaluates expressions. It never clicks
 * anything that changes state.
 *
 * Usage: node tools/sidebar-inspect.js [url] [width] [height]
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const url = process.argv[2] || 'http://127.0.0.1:3080/';
const width = Number(process.argv[3] || 1000);
const height = Number(process.argv[4] || 2176);

const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => fs.existsSync(p));
if (!CHROME) {
  console.error('no chrome/edge found');
  process.exit(2);
}

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-inspect-'));
const port = 9222 + Math.floor(Math.random() * 500);

const browser = spawn(CHROME, [
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`,
  '--headless=new',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  `--window-size=${width},${height}`,
  'about:blank',
], { stdio: 'ignore', windowsHide: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpTargets() {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/list`);
      const list = await res.json();
      const page = list.find((t) => t.type === 'page');
      if (page?.webSocketDebuggerUrl) return page;
    } catch { /* not up yet */ }
    await sleep(250);
  }
  throw new Error('chrome devtools endpoint never came up');
}

let msgId = 1;
function rpc(ws, method, params = {}, sessionId) {
  const id = msgId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${method} timed out`)), 30000);
    const onMsg = (raw) => {
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      if (m.id !== id) return;
      clearTimeout(timer);
      ws.off('message', onMsg);
      if (m.error) reject(new Error(`${method}: ${m.error.message}`));
      else resolve(m.result);
    };
    ws.on('message', onMsg);
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}

/** Evaluate an expression in the page and return its JSON value. */
async function evaluate(ws, sessionId, expression) {
  const r = await rpc(ws, 'Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  }, sessionId);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text ?? 'eval failed');
  return r.result.value;
}

const REPORT = `(() => {
  const out = {};
  out.viewport = { w: innerWidth, h: innerHeight, dpr: devicePixelRatio };
  out.ua = navigator.userAgent.slice(0, 80);

  const frame = document.querySelector('[class*="_frame"]');
  if (!frame) { out.frame = null; return out; }

  const cs = getComputedStyle(frame);
  out.frame = {
    columns: cs.gridTemplateColumns,
    sidebarCollapsedAttr: frame.getAttribute('data-sidebar-collapsed'),
    detailsCollapsedAttr: frame.getAttribute('data-details-collapsed'),
  };

  const col = frame.querySelector('[class*="_sidebarCol"]');
  if (col) {
    const r = col.getBoundingClientRect();
    out.sidebarColumn = { x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height) };
  }

  // The toggle is the only in-sidebar close affordance.
  const toggles = [...document.querySelectorAll('button')]
    .filter(b => (b.getAttribute('aria-label') || '').match(/侧边栏|sidebar/i));
  out.toggles = toggles.map(b => {
    const r = b.getBoundingClientRect();
    const s = getComputedStyle(b);
    const cx = Math.round(r.x + r.width / 2), cy = Math.round(r.y + r.height / 2);
    const hit = (cx >= 0 && cy >= 0 && cx < innerWidth && cy < innerHeight)
      ? document.elementFromPoint(cx, cy) : null;
    return {
      label: b.getAttribute('aria-label'),
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      inViewport: r.x >= 0 && r.right <= innerWidth && r.width > 0 && r.height > 0,
      display: s.display, visibility: s.visibility, opacity: s.opacity,
      pointerEvents: s.pointerEvents, zIndex: s.zIndex,
      hitAtCentre: hit ? hit.tagName + '.' + String(hit.getAttribute('class') || '').slice(0, 40) : null,
      centreHitsButton: hit ? (hit === b || b.contains(hit)) : null,
    };
  });

  // What sits in the top-left strip, where a close control would be expected?
  out.corner = [
    { x: 14, y: 14 }, { x: 40, y: 30 }, { x: innerWidth - 20, y: 20 },
  ].map(p => {
    const el = document.elementFromPoint(p.x, p.y);
    return { at: p, tag: el ? el.tagName : null,
             cls: el ? String(el.getAttribute('class') || '').slice(0, 50) : null };
  });

  return out;
})()`;

(async () => {
  const page = await cdpTargets();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 64 * 1024 * 1024 });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });

  const { sessionId } = await rpc(ws, 'Target.attachToTarget', { targetId: page.id, flatten: true });
  await rpc(ws, 'Page.enable', {}, sessionId);
  // A phone-sized viewport with a touch flag, so the page takes its narrow path.
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 2, mobile: true,
  }, sessionId);

  console.log(`browser : ${path.basename(CHROME)}`);
  console.log(`url     : ${url}`);
  console.log(`viewport: ${width}x${height} (mobile emulation)`);
  console.log('');

  await rpc(ws, 'Page.navigate', { url }, sessionId);
  await sleep(9000); // let the SPA boot and settle

  const report = await evaluate(ws, sessionId, REPORT);
  console.log(JSON.stringify(report, null, 2));

  // Read the sidebar's own column state too: whether it believes it is expanded.
  const state = await evaluate(ws, sessionId, `(() => {
    const col = document.querySelector('[class*="_sidebarCol"]');
    const root = col && col.firstElementChild;
    const r = root ? root.getBoundingClientRect() : null;
    return {
      sidebarRootClass: root ? String(root.getAttribute('class') || '') : null,
      sidebarRootRect: r ? { x: Math.round(r.x), w: Math.round(r.width) } : null,
    };
  })()`);
  console.log('');
  console.log('--- sidebar root ---');
  console.log(JSON.stringify(state, null, 2));

  ws.close();
  browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((err) => {
  console.error('inspect failed:', err.message);
  browser.kill();
  process.exit(1);
});
