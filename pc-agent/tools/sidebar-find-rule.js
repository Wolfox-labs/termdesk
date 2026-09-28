/**
 * Find the exact declaration that computes the collapse button to display:none.
 *
 * Earlier passes established: the button exists with aria-label "收起侧边栏",
 * carries `dshp-panel__toggle` (from the dsh-tauri-panel plugin, which replaces
 * the core sidebar), and computes to `display:none` only while the sidebar is
 * OPEN. A rule-level scan of document.styleSheets found no matching declaration,
 * and the class text appears in no installed package, so the hiding rule is
 * injected at runtime in a form the naive scan missed.
 *
 * This uses CDP's own CSS domain, which resolves the cascade the way the engine
 * does, and falls back to reading every sheet's raw text for the class.
 *
 * Read-only.
 *
 * Usage: node tools/sidebar-find-rule.js [url] [width] [height]
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

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-rule-'));
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
const pending = new Map();
function rpc(ws, method, params = {}, sessionId) {
  const mine = id++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(mine); reject(new Error(method + ' timed out')); }, 40000);
    pending.set(mine, { resolve, reject, timer });
    ws.send(JSON.stringify({ id: mine, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}

(async () => {
  const page = await target();
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 128 * 1024 * 1024 });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });

  const events = [];
  ws.on('message', (raw) => {
    let m; try { m = JSON.parse(raw.toString()); } catch { return; }
    if (m.id !== undefined) {
      const slot = pending.get(m.id);
      if (!slot) return;
      pending.delete(m.id);
      clearTimeout(slot.timer);
      m.error ? slot.reject(new Error(m.error.message)) : slot.resolve(m.result);
      return;
    }
    events.push(m);
  });

  const { sessionId: s } = await rpc(ws, 'Target.attachToTarget', { targetId: page.id, flatten: true });
  await rpc(ws, 'Page.enable', {}, s);
  await rpc(ws, 'DOM.enable', {}, s);
  await rpc(ws, 'CSS.enable', {}, s);
  await rpc(ws, 'Emulation.setDeviceMetricsOverride',
    { width: W, height: H, deviceScaleFactor: 2, mobile: true }, s);

  await rpc(ws, 'Page.navigate', { url }, s);
  await sleep(9000);

  // Open the sidebar so the hiding rule applies.
  await rpc(ws, 'Runtime.evaluate', {
    expression: `(() => { const b=[...document.querySelectorAll('button')]
      .find(x=>/打开侧边栏/.test(x.getAttribute('aria-label')||'')); if(b) b.click(); return true; })()`,
    returnByValue: true,
  }, s);
  await sleep(1800);

  // Find the node, then ask the engine which rules matched it.
  const { root } = await rpc(ws, 'DOM.getDocument', { depth: -1, pierce: true }, s);
  const { nodeId } = await rpc(ws, 'DOM.querySelector', {
    nodeId: root.nodeId,
    selector: 'button[aria-label="收起侧边栏"]',
  }, s);

  if (!nodeId) {
    console.log('collapse button not found in DOM while open');
  } else {
    const matched = await rpc(ws, 'CSS.getMatchedStylesForNode', { nodeId }, s);
    console.log('=== matched rules that affect display/visibility/width ===');
    for (const entry of matched.matchedCSSRules ?? []) {
      const rule = entry.rule;
      const sel = rule.selectorList?.text ?? '';
      const props = (rule.style?.cssProperties ?? [])
        .filter((p) => /^(display|visibility|width|height|opacity|flex)$/.test(p.name))
        .map((p) => `${p.name}: ${p.value}${p.disabled ? ' (disabled)' : ''}${p.implicit ? ' (implicit)' : ''}`);
      if (props.length === 0) continue;
      console.log(`  ${sel}   [${rule.origin}]`);
      console.log(`      ${props.join('; ')}`);
      if (rule.media?.length) console.log(`      media: ${rule.media.map((m) => m.text).join(' && ')}`);
    }

    // Also report the raw text of any sheet containing the class, which catches
    // rules the CSS agent does not surface.
    const raw = await rpc(ws, 'Runtime.evaluate', {
      expression: `(() => {
        const out = [];
        for (const sh of document.styleSheets) {
          let text; try { text = [...sh.cssRules].map(r => r.cssText).join('\\n'); } catch { continue; }
          if (!text.includes('dshp-panel__toggle')) continue;
          const idx = text.indexOf('dshp-panel__toggle');
          out.push({ href: sh.href || '(inline)', around: text.slice(Math.max(0, idx - 200), idx + 300) });
        }
        return out;
      })()`,
      returnByValue: true,
    }, s);
    console.log('');
    console.log('=== raw sheets mentioning the class ===');
    console.log(JSON.stringify(raw.result.value, null, 2));
  }

  ws.close(); browser.kill();
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
  process.exit(0);
})().catch((e) => { console.error('failed:', e.message); browser.kill(); process.exit(1); });
