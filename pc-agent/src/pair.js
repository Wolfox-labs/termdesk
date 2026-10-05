/**
 * Pairing: one string that carries everything the phone needs.
 *
 * The payload is a custom-scheme URI, so the phone's own camera app can scan the
 * code and hand it straight to TermDesk — no in-app scanner, no typing a 43
 * character token by thumb.
 *
 *   termdesk://pair?url=wss://host&token=...&name=yaosw
 */
import QRCode from 'qrcode';

export function pairPayload({ wsUrl, token, name }) {
  const params = new URLSearchParams();
  params.set('url', wsUrl);
  params.set('token', token);
  if (name) params.set('name', name);
  return `termdesk://pair?${params.toString()}`;
}

/** SVG string, so the page needs no image encoding and scales on any screen. */
export async function qrSvg(text, { width = 280 } = {}) {
  return await QRCode.toString(text, {
    type: 'svg',
    errorCorrectionLevel: 'M',
    margin: 1,
    width,
  });
}

/**
 * The QR as a module grid, for clients that draw it themselves.
 *
 * The pairing page gets an SVG, but the desktop window renders with Compose and
 * has no SVG pipeline — adding one would be a dependency for a rectangle grid.
 * The agent already owns the encoder, so it hands out the grid and the client
 * just paints squares. Rows are '0'/'1' strings to keep the JSON small.
 */
export function qrMatrix(text, { errorCorrectionLevel = 'M' } = {}) {
  const code = QRCode.create(text, { errorCorrectionLevel });
  const { size, data } = code.modules;
  const rows = [];
  for (let y = 0; y < size; y += 1) {
    let row = '';
    for (let x = 0; x < size; x += 1) row += data[y * size + x] ? '1' : '0';
    rows.push(row);
  }
  return { size, rows };
}

/** Block-character QR for the terminal, for when no browser is open. */
export async function qrTerminal(text) {
  return await QRCode.toString(text, { type: 'terminal', small: true });
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * The pairing page.
 *
 * Loopback only (the caller enforces it): it contains the token, so it must
 * never be reachable through the tunnel it is describing.
 */
export async function pairPage({ payload, wsUrl, token, tunnel, lanUrls, appUrl, expiresAt }) {
  const qr = await qrSvg(payload);
  const rows = [
    ['连接地址', wsUrl],
    ['配对令牌', token],
  ].map(([label, value]) => `
      <div class="row">
        <div class="label">${escapeHtml(label)}</div>
        <div class="value">${escapeHtml(value)}</div>
      </div>`).join('');

  const tunnelLine = tunnel?.url
    ? `公网地址：${escapeHtml(tunnel.url)}（${tunnel.mode === 'named' ? '固定域名' : '临时地址，重启会变'}）`
    : `未启动公网隧道。本机地址：${escapeHtml((lanUrls ?? []).join('  ·  ') || '—')}`;

  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TermDesk 配对</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
         background: #272822; color: #f8f8f2; display: flex; justify-content: center; }
  main { max-width: 560px; width: 100%; padding: 28px 20px 48px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.sub { margin: 0 0 20px; color: #a6a28c; font-size: 13px; line-height: 1.6; }
  .qr { background: #fff; padding: 14px; border-radius: 14px; display: inline-block; }
  .qr svg { display: block; width: 260px; height: 260px; }
  ol { color: #a6a28c; font-size: 13px; line-height: 1.8; padding-left: 20px; margin: 18px 0; }
  .row { display: flex; gap: 10px; padding: 7px 0; border-bottom: 1px solid #3e3d32; font-size: 13px; }
  .label { color: #a6a28c; min-width: 76px; }
  .value { font-family: ui-monospace, Consolas, monospace; word-break: break-all; }
  .note { margin-top: 18px; color: #a6a28c; font-size: 12px; line-height: 1.7; }
</style>
</head>
<body>
<main>
  <h1>TermDesk 配对</h1>
  <p class="sub">用手机相机扫下面这个码，手机会直接打开 TermDesk 并完成配对。</p>
  <div class="qr">${qr}</div>
  <ol>
    <li>手机上先装好 App（见下方链接；同签名会原地升级）</li>
    <li>用手机相机对准上面的码，识别出的链接点开会跳到 TermDesk</li>
    <li>App 自动填入地址与令牌并连接，之后换网也会自动重连</li>
  </ol>
  <div class="row"><div class="label">安装/升级</div>
    <div class="value">${escapeHtml(appUrl ?? '')}</div></div>
  <div class="row"><div class="label">手机打开</div>
    <div class="value">用手机浏览器打开上面的安装地址即可</div></div>
  ${rows}
  <div class="note">
    ${tunnelLine}<br>
    这个页面只在本机可访问（127.0.0.1），不会通过隧道暴露；离开本机请勿分享上面的令牌。
    ${expiresAt ? `<br>页面生成于 ${escapeHtml(new Date(expiresAt).toLocaleString())}` : ''}
  </div>
</main>
</body>
</html>`;
}
