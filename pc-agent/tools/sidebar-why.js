/**
 * Why is the left sidebar's collapse button zero-sized once the sidebar is open?
 *
 * sidebar-open-check.js proved the button exists with a 0x0 rect at phone width,
 * which makes it untappable; the user is stuck with an open sidebar. A 0x0 rect
 * usually means `display:none`, but that could come from the sidebar's own CSS,
 * from a media query, or from a plugin skin. This walks the real computed style
 * and the ancestor chain so the cause is identified rather than guessed.
 *
 * Usage: node tools/sidebar-why.js [url] [width] [height]
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';

const url = process.argv[2] || 'http://127.0.0.1:3080/';
const W = Number(process.argv[3] || 412);
const H = Number(process.argv[4] || 915);

const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => fs.existsSync(p));

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-why-'));
const port = 9222 + Math.floor(Math.random() * 400);
const browser = spawn(CHROME, [
  `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  '--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  `--window-size=${W},${H}`, 'about:blank',
], { stdio: 'ignore', windowsHide: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function target() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const p = list.find((t) => t.type === 'page');
      if (p?.webSocketDebuggerUrl) return p;
    } catch { /* booting */ }
    await sleep(250);
  }
  throw new Error('devtools never came up');
}

let id = 1;
function rpc(ws, method, params = {}, sessionId) {
  const mine = id++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(method + ' timed out')), 30000);
    const onMsg = (raw) => {
      let m; try { m = JSON.parse(raw.toString()); } catch { return; }
      if (m.id !== mine) return;
      clearTimeout(timer); ws.off('message', onMsg);
      m.error ? reject(new Error(method + ': ' + m.error.message)) : resolve(m.result);
    };
    ws.on('message', onMsg);
    ws.send(JSON.stringify({ id: mine, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
async function ev(ws, s, expression) {
  const r = await rpc(ws, 'Runtime.evaluate',
    { expression, returnByValue: true, awaitPromise: true }, s);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text ?? 'eval failed');
  return r.result.value;
}

/**
 * Report the collapse button's computed box and every ancestor's own box, so a
 * collapsed parent is distinguishable from a hidden button.
 */
const WHY = `(() => {
  const btn = [...document.querySelectorAll('button')]
    .find(b => /收起侧边栏/.test(b.getAttribute('aria-label') || ''));
  if (!btn) return { found: false, note: 'no collapse button in DOM at all' };

  const cs = getComputedStyle(btn);
  const r = btn.getBoundingClientRect();

  const chain = [];
  let el = btn;
  let depth = 0;
  while (el && depth < 8) {
    const s = getComputedStyle(el);
    const b = el.getBoundingClientRect();
    chain.push({
      tag: el.tagName,
      cls: String(el.getAttribute('class') || '').slice(0, 70),
      display: s.display, visibility: s.visibility, overflow: s.overflow,
      width: s.width, minWidth: s.minWidth, flex: s.flex, position: s.position,
      rect: { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) },
      offsetParentNull: el.offsetParent === null,
    });
    el = el.parentElement;
    depth++;
  }

  return {
    found: true,
    button: {
      ariaLabel: btn.getAttribute('aria-label'),
      display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
      width: cs.width, height: cs.height, flex: cs.flex, position: cs.position,
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      childCount: btn.children.length,
      innerHTML: btn.innerHTML.slice(0, 120),
    },
    ancestors: chain,
  };
})()`;

(async () => {
  const page = await target();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 64 * 1024 * 1024 });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  const { sessionId: s } = await rpc(ws, 'Target.attachToTarget', { targetId: page.id, flatten: true });
  await rpc(ws, 'Page.enable', {}, s);
  await rpc(ws, 'Emulation.setDeviceMetricsOverride',
    { width: W, height: H, deviceScaleFactor: 2, mobile: true }, s);

  console.log(`viewport ${W}x${H}`);
  await rpc(ws, 'Page.navigate', { url }, s);
  await sleep(9000);

  // Open the sidebar first: the bug only appears while it is open.
  await ev(ws, s, `(() => {
    const b = [...document.querySelectorAll('button')]
      .find(x => /打开侧边栏/.test(x.getAttribute('aria-label') || ''));
    if (b) b.click();
    return true;
  })()`);
  await sleep(1800);

  console.log('');
  console.log('=== collapse button, sidebar OPEN ===');
  console.log(JSON.stringify(await ev(ws, s, WHY), null, 2));

  ws.close(); browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((e) => { console.error('failed:', e.message); browser.kill(); process.exit(1); });
