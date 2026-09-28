/**
 * Reproduce the phone sidebar problem: open the left sidebar at phone width,
 * then find out whether a control that closes it is actually reachable.
 *
 * The previous run only showed the collapsed state, because at 1000px the
 * layout auto-collapses. The user's complaint is about the EXPANDED state: once
 * open, nothing closes it. So this drives the real interaction:
 *
 *   1. load at phone width
 *   2. click the "open sidebar" control
 *   3. wait for the expand transition
 *   4. measure every control that could close it, and what is painted over it
 *
 * Usage: node tools/sidebar-open-check.js [url] [width] [height]
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

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-open-'));
const port = 9222 + Math.floor(Math.random() * 500);
const browser = spawn(CHROME, [
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`,
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

/** Everything that could close the sidebar, and whether a tap would reach it. */
const SURVEY = `(() => {
  const out = { viewport: { w: innerWidth, h: innerHeight } };
  const frame = document.querySelector('[class*="_frame"]');
  out.columns = frame ? getComputedStyle(frame).gridTemplateColumns : null;
  out.collapsedAttr = frame ? frame.getAttribute('data-sidebar-collapsed') : null;

  const col = frame && frame.querySelector('[class*="_sidebarCol"]');
  if (col) { const r = col.getBoundingClientRect();
    out.sidebarCol = { x: Math.round(r.x), w: Math.round(r.width) }; }

  const candidates = [...document.querySelectorAll('button')].filter(b =>
    /侧边栏|sidebar/i.test(b.getAttribute('aria-label') || ''));
  out.buttons = candidates.map(b => {
    const r = b.getBoundingClientRect();
    const cs = getComputedStyle(b);
    const cx = Math.round(r.x + r.width/2), cy = Math.round(r.y + r.height/2);
    const inside = cx >= 0 && cy >= 0 && cx < innerWidth && cy < innerHeight;
    const hit = inside ? document.elementFromPoint(cx, cy) : null;
    return {
      label: b.getAttribute('aria-label'),
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      reachable: inside && cs.visibility === 'visible' && cs.pointerEvents !== 'none'
                 && !!hit && (hit === b || b.contains(hit)),
      hit: hit ? hit.tagName + '.' + String(hit.getAttribute('class') || '').slice(0, 30) : null,
    };
  });

  // Is there ANY element in the sidebar's own column that looks like a close
  // affordance, regardless of its label?
  if (col) {
    out.sidebarButtons = [...col.querySelectorAll('button')].map(b => {
      const r = b.getBoundingClientRect();
      return {
        label: (b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 24),
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      };
    }).slice(0, 14);
  }
  return out;
})()`;

(async () => {
  const page = await target();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 64 * 1024 * 1024 });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  const { sessionId: s } = await rpc(ws, 'Target.attachToTarget', { targetId: page.id, flatten: true });
  await rpc(ws, 'Page.enable', {}, s);
  await rpc(ws, 'Emulation.setDeviceMetricsOverride',
    { width: W, height: H, deviceScaleFactor: 2, mobile: true }, s);

  console.log(`viewport ${W}x${H} (phone emulation)`);
  await rpc(ws, 'Page.navigate', { url }, s);
  await sleep(9000);

  console.log('');
  console.log('=== collapsed on load ===');
  console.log(JSON.stringify(await ev(ws, s, SURVEY), null, 2));

  // Open it the way a person would: tap the toggle intended for that.
  const opened = await ev(ws, s, `(() => {
    const b = [...document.querySelectorAll('button')]
      .find(x => /打开侧边栏/.test(x.getAttribute('aria-label') || ''));
    if (!b) return 'no open button';
    b.click();
    return 'clicked';
  })()`);
  console.log('');
  console.log('open action: ' + opened);
  await sleep(1600);

  console.log('');
  console.log('=== after opening (the state the user is stuck in) ===');
  const after = await ev(ws, s, SURVEY);
  console.log(JSON.stringify(after, null, 2));

  const closers = (after.buttons || []).filter((b) => /收起|关闭|collapse/i.test(b.label));
  console.log('');
  if (closers.length === 0) {
    console.log('VERDICT: no collapse control exists once the sidebar is open.');
  } else if (closers.every((b) => !b.reachable)) {
    console.log('VERDICT: a collapse control exists but is NOT reachable by a tap.');
    console.log(JSON.stringify(closers, null, 2));
  } else {
    console.log('VERDICT: a reachable collapse control exists:');
    console.log(JSON.stringify(closers.filter((b) => b.reachable), null, 2));
  }

  ws.close(); browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((e) => { console.error('failed:', e.message); browser.kill(); process.exit(1); });
