/**
 * Dump the raw stylesheet text that owns the sidebar collapse button's classes.
 *
 * Earlier attempts failed in two ways: the class prefix `dshp-` appears in no
 * installed package, and a rule-level scan found no matching declaration even
 * though `getComputedStyle` reports `display:none`. Both point at rules the CDP
 * rule walker cannot see (constructed sheets, adopted stylesheets, or nested
 * CSS). This reads the sheets' raw text instead, which catches all of those.
 *
 * Read-only.
 *
 * Usage: node tools/sidebar-css.js [url] [width] [height]
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

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-css-'));
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
    const timer = setTimeout(() => reject(new Error(method + ' timed out')), 40000);
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

(async () => {
  const page = await target();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 128 * 1024 * 1024 });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  const { sessionId: s } = await rpc(ws, 'Target.attachToTarget', { targetId: page.id, flatten: true });
  await rpc(ws, 'Page.enable', {}, s);
  await rpc(ws, 'Emulation.setDeviceMetricsOverride',
    { width: W, height: H, deviceScaleFactor: 2, mobile: true }, s);

  await rpc(ws, 'Page.navigate', { url }, s);
  await sleep(9000);
  await ev(ws, s, `(() => {
    const b = [...document.querySelectorAll('button')]
      .find(x => /打开侧边栏/.test(x.getAttribute('aria-label') || ''));
    if (b) b.click();
    return true;
  })()`);
  await sleep(1800);

  // 1. every stylesheet: where it came from, and whether it mentions the class.
  const sheets = await ev(ws, s, `(() => {
    const out = [];
    for (const sh of document.styleSheets) {
      let text = '';
      try { text = [...sh.cssRules].map(r => r.cssText).join('\\n'); } catch { text = '(unreadable)'; }
      out.push({
        owner: sh.ownerNode ? sh.ownerNode.tagName + (sh.ownerNode.id ? '#' + sh.ownerNode.id : '') : (sh.href ? 'link' : 'constructed'),
        href: sh.href || '(inline)',
        bytes: text.length,
        mentionsDshp: text.includes('dshp'),
        mentionsToggle: text.includes('__toggle'),
      });
    }
    return out;
  })()`);
  console.log('=== stylesheets ===');
  console.log(JSON.stringify(sheets, null, 2));

  // 2. the raw declarations that target the toggle, wherever they live.
  const rules = await ev(ws, s, `(() => {
    const found = [];
    const scan = (text, origin) => {
      const re = /([^{}]*dshp[^{}]*)\\{([^}]*)\\}/g;
      let m;
      while ((m = re.exec(text)) !== null) {
        const sel = m[1].trim();
        const body = m[2].trim();
        if (!/toggle|logo-row|icon-button/.test(sel)) continue;
        found.push({ origin, selector: sel.slice(0, 200), body: body.slice(0, 260) });
      }
    };
    for (const sh of document.styleSheets) {
      let text = '';
      try { text = [...sh.cssRules].map(r => r.cssText).join('\\n'); } catch { continue; }
      // Media-wrapped rules keep their @media text inline; scan the whole sheet.
      scan(text, sh.href || (sh.ownerNode ? sh.ownerNode.tagName + (sh.ownerNode.id ? '#' + sh.ownerNode.id : '') : '?'));
    }
    return found;
  })()`);
  console.log('');
  console.log('=== rules targeting the toggle ===');
  console.log(JSON.stringify(rules, null, 2));

  // 3. inline <style> blocks, which is where a runtime skin would live.
  const inline = await ev(ws, s, `(() => {
    return [...document.querySelectorAll('style')].map((st, i) => ({
      index: i,
      bytes: (st.textContent || '').length,
      hasDshp: (st.textContent || '').includes('dshp'),
      head: (st.textContent || '').slice(0, 160),
    })).filter(x => x.hasDshp || x.bytes > 500);
  })()`);
  console.log('');
  console.log('=== inline style blocks ===');
  console.log(JSON.stringify(inline, null, 2));

  ws.close(); browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((e) => { console.error('failed:', e.message); browser.kill(); process.exit(1); });
