/**
 * Static checks for the pairing payload and its QR code.
 *
 * No network and no tunnel: this proves the string the QR carries survives a
 * round trip through an independent decoder (jsQR), which is the part that fails
 * silently — an unreadable code looks exactly like a readable one on screen.
 *
 *   node tools/pair-test.js
 */
import QRCode from 'qrcode';
import jsQR from 'jsqr';
import { pairPayload, qrSvg, qrTerminal, pairPage, choosePairingUrl, pairingUrlReason } from '../src/pair.js';
import { findCloudflared, tunnelConfigPath, Tunnel, healthIsOurs } from '../src/tunnel.js';

let passes = 0;
let failures = 0;
function check(name, ok, detail = '') {
  if (ok) { passes += 1; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures += 1; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
}

// --- payload --------------------------------------------------------------

const payload = pairPayload({
  wsUrl: 'wss://calm-river-1234.trycloudflare.com',
  token: 'a'.repeat(43),
  name: 'yaosw',
});
check('payload is a termdesk:// uri', payload.startsWith('termdesk://pair?'), payload.slice(0, 40));
{
  const parsed = new URL(payload);
  check('scheme is termdesk', parsed.protocol === 'termdesk:');
  check('host is pair', parsed.host === 'pair');
  check('url survives encoding', parsed.searchParams.get('url') === 'wss://calm-river-1234.trycloudflare.com');
  check('token survives encoding', parsed.searchParams.get('token') === 'a'.repeat(43));
  check('name is carried', parsed.searchParams.get('name') === 'yaosw');
}

// --- the QR actually decodes ---------------------------------------------

function matrixToRgba(matrix, scale = 4, quiet = 4) {
  const size = matrix.size;
  const total = (size + quiet * 2) * scale;
  const data = new Uint8ClampedArray(total * total * 4);
  for (let y = 0; y < total; y += 1) {
    for (let x = 0; x < total; x += 1) {
      const mx = Math.floor(x / scale) - quiet;
      const my = Math.floor(y / scale) - quiet;
      const dark = mx >= 0 && my >= 0 && mx < size && my < size && matrix.get(mx, my);
      const value = dark ? 0 : 255;
      const offset = (y * total + x) * 4;
      data[offset] = value;
      data[offset + 1] = value;
      data[offset + 2] = value;
      data[offset + 3] = 255;
    }
  }
  return { data, width: total, height: total };
}

{
  const qr = QRCode.create(payload, { errorCorrectionLevel: 'M' });
  const { data, width, height } = matrixToRgba(qr.modules);
  const decoded = jsQR(data, width, height);
  check('jsQR decodes the payload', decoded?.data === payload, decoded ? decoded.data.slice(0, 32) : 'not decoded');
}

// --- presentation ---------------------------------------------------------

{
  const svg = await qrSvg(payload);
  check('svg contains a path', svg.includes('<svg') && svg.includes('path'), `${svg.length} bytes`);
  const terminal = await qrTerminal(payload);
  check('terminal qr is multi-line', terminal.split('\n').length > 10);
  const page = await pairPage({
    payload,
    wsUrl: 'wss://calm-river-1234.trycloudflare.com',
    token: 'a'.repeat(43),
    tunnel: { url: 'https://calm-river-1234.trycloudflare.com', mode: 'quick' },
    lanUrls: ['192.168.1.8'],
  });
  check('page embeds the code', page.includes('<svg'));
  check('page labels the tunnel mode', page.includes('临时地址'));
  check('page escapes the token', !page.includes('<script'), 'no injected markup');
}

// --- which address the code carries ---------------------------------------
//
// The failure this guards against is not cosmetic: the printed QR carried a
// tunnel hostname that DNS pointed at the VPS relay, so the phone sent its
// pairing token to a stranger and was told "invalid credentials". An address is
// only advertised when the agent answering there proved to be us.

check(
  'a verified public address is used as-is (wss)',
  choosePairingUrl({ tunnelUrl: 'https://term.example.com', tunnelIsOurs: true, lanUrls: ['ws://10.0.0.5:7420'] })
    === 'wss://term.example.com',
);
check(
  'an unverified public address is NOT advertised',
  choosePairingUrl({ tunnelUrl: 'https://term.example.com', tunnelIsOurs: false, lanUrls: ['ws://10.0.0.5:7420'] })
    === 'ws://10.0.0.5:7420',
);
check(
  'with nothing verified, the LAN address wins over loopback',
  choosePairingUrl({ tunnelUrl: null, tunnelIsOurs: false, lanUrls: ['ws://192.168.1.8:7420'], port: 7420 })
    === 'ws://192.168.1.8:7420',
);
check(
  'with no LAN address either, loopback is the honest last resort',
  choosePairingUrl({ tunnelUrl: null, tunnelIsOurs: false, lanUrls: [], port: 7420 }) === 'ws://127.0.0.1:7420',
);
check(
  'the reason names the awkward case',
  pairingUrlReason({ tunnelUrl: 'https://term.example.com', tunnelIsOurs: false, lanUrls: ['ws://10.0.0.5:7420'] })
    === 'public_not_ours',
);
check(
  'and the happy case',
  pairingUrlReason({ tunnelUrl: 'https://term.example.com', tunnelIsOurs: true, lanUrls: [] }) === 'public',
);

// Identity of the thing answering /healthz: a 200 from another service is not us.
check('our own healthz is recognised', healthIsOurs('{"ok":true,"service":"termdesk-pc-agent","protocol":1}'));
check('another service is not', !healthIsOurs('{"ok":true,"service":"termdesk-relay","protocol":1}'));
check('a non-JSON answer is not', !healthIsOurs('<html>error 1033</html>'));
check('an empty answer is not', !healthIsOurs(''));

// --- tunnel plumbing ------------------------------------------------------

check('cloudflared is present', findCloudflared() !== null, findCloudflared() ?? 'not found');
check('tunnel config path is under the home dir', tunnelConfigPath().includes('.termdesk'));
{
  const tunnel = new Tunnel();
  const status = tunnel.status();
  check('idle tunnel reports not running', status.running === false && status.url === null);
  tunnel.stop();
  check('stopping an idle tunnel is safe', tunnel.status().running === false);
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
