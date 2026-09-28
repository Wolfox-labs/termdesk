/**
 * Identify which plugin hides the left sidebar's collapse button.
 *
 * sidebar-why.js proved the button carries `dshp-panel__toggle` and computes to
 * `display:none` while the sidebar is open, so the user cannot close it. That
 * class prefix is not found in any installed package, which means the rule is
 * injected at runtime. This asks the browser which stylesheet owns the rule and
 * which of the loaded plugin bundles wrote it.
 *
 * Read-only.
 *
 * Usage: node tools/sidebar-owner.js [url] [width] [height]
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

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-owner-'));
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

/** Which CSS rules match the collapse button, and who wrote them. */
const OWNER = `(() => {
  const btn = [...document.querySelectorAll('button')]
    .find(b => /收起侧边栏/.test(b.getAttribute('aria-label') || ''));
  if (!btn) return { found: false };

  const hits = [];
  for (const sheet of document.styleSheets) {
    let rules;
    try { rules = sheet.cssRules; } catch { continue; }  // cross-origin sheet
    const walk = (list) => {
      for (const rule of list) {
        if (rule.cssRules) { walk(rule.cssRules); continue; }   // @media etc.
        if (!rule.selectorText) continue;
        let matches = false;
        try { matches = btn.matches(rule.selectorText); } catch { continue; }
        if (!matches) continue;
        const cssText = rule.cssText;
        if (!/display|visibility|width|opacity/.test(cssText)) continue;
        hits.push({
          selector: rule.selectorText.slice(0, 160),
          css: cssText.slice(0, 220),
          isMedia: false,
          sheetOwner: sheet.ownerNode
            ? (sheet.ownerNode.tagName + (sheet.ownerNode.id ? '#' + sheet.ownerNode.id : ''))
            : '(constructed)',
          href: sheet.href || null,
        });
      }
    };
    walk(rules);
  }

  // Rules inside media queries need separate handling to name the condition.
  const mediaHits = [];
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch { continue; }
    for (const rule of rules) {
      if (!rule.media || !rule.cssRules) continue;
      const cond = rule.conditionText || rule.media.mediaText;
      for (const inner of rule.cssRules) {
        if (!inner.selectorText) continue;
        let matches = false;
        try { matches = btn.matches(inner.selectorText); } catch { continue; }
        if (!matches) continue;
        if (!/display|visibility|opacity/.test(inner.cssText)) continue;
        mediaHits.push({ media: cond, selector: inner.selectorText.slice(0, 140), css: inner.cssText.slice(0, 200) });
      }
    }
  }

  // Does a stylesheet mention the dshp- prefix at all, and can we see its text?
  const owners = [];
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch { continue; }
    const text = [...rules].map(r => r.cssText).join('\\n');
    if (text.includes('dshp-panel__toggle') || text.includes('dshp-panel__logo-row')) {
      owners.push({
        owner: sheet.ownerNode ? sheet.ownerNode.tagName + (sheet.ownerNode.id ? '#' + sheet.ownerNode.id : '') : '?',
        href: sheet.href || '(inline)',
        snippet: (text.match(/\\.dshp-panel__toggle[^}]*\\}/g) || []).slice(0, 4),
      });
    }
  }

  return { found: true, matchedRules: hits, mediaRules: mediaHits, dshpSheets: owners };
})()`;

(async () => {
  const page = await target();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 64 * 1024 * 1024 });
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

  console.log(JSON.stringify(await ev(ws, s, OWNER), null, 2));
  ws.close(); browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((e) => { console.error('failed:', e.message); browser.kill(); process.exit(1); });
