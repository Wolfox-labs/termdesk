/**
 * The production pairing path, checked from this machine.
 *
 * What it proves, in one run: the code this agent hands out works over the public
 * address, the relay routes the phone to *this* computer, the agent itself answers
 * (not the relay), a real request travels through it, and the phone can unbind
 * itself again. That is the whole chain the phone will use, minus the phone.
 *
 *   node tools/relay-public-check.mjs                 # agent on 127.0.0.1:7420
 *   node tools/relay-public-check.mjs --port 7421 --name "我的手机"
 *
 * It needs the agent's own control endpoints, so it only talks to loopback; the
 * pairing itself goes over the public address the agent advertises.
 */
import os from 'node:os';
import WebSocket from 'ws';

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const port = Number(arg('port', process.env.TERMDESK_PORT ?? 7420));
const phoneName = arg('name', '验收手机');
const base = `http://127.0.0.1:${port}`;

let passes = 0, failures = 0;
const check = (label, ok, detail = '') => {
  if (ok) { passes += 1; console.log(`  PASS  ${label}${detail ? ' :: ' + detail : ''}`); }
  else { failures += 1; console.log(`  FAIL  ${label}${detail ? ' :: ' + detail : ''}`); }
};

function open(url) {
  const ws = new WebSocket(url, { handshakeTimeout: 15_000, maxPayload: 8 * 1024 * 1024 });
  const queue = [], waiters = [];
  ws.on('message', (raw) => {
    let f; try { f = JSON.parse(raw.toString()); } catch { return; }
    const i = waiters.findIndex((w) => w.p(f));
    if (i >= 0) { const w = waiters.splice(i, 1)[0]; clearTimeout(w.timer); w.resolve(f); } else queue.push(f);
  });
  const wait = (type, p = () => true, ms = 10_000) => {
    const predicate = (f) => f.type === type && p(f);
    const i = queue.findIndex(predicate);
    if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { p: predicate, resolve, timer: null };
      w.timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error(`等不到 ${type}`)); }, ms);
      waiters.push(w);
    });
  };
  return { ws, wait, send: (f) => ws.send(JSON.stringify(f)), ready: new Promise((r, j) => { ws.on('open', r); ws.on('error', j); }) };
}

let phone = null, deviceId = null;
try {
  const pair = await (await fetch(`${base}/pair.json`)).json();
  check('代理给出的是中转配对', pair.relay === true && String(pair.url).startsWith('wss://'), String(pair.url));
  check('配对码是一次性的短码', /^[0-9A-Z]{4}(-[0-9A-Z]{4}){2}$/.test(pair.code ?? ''), String(pair.code));
  check('二维码里带上了中转标记', String(pair.payload ?? '').includes('relay=1'));

  phone = open(String(pair.url).replace(/\/$/, '') + '/client');
  await phone.ready;
  phone.send({ type: 'auth', token: pair.code, device: { name: phoneName } });
  const paired = await phone.wait('device.paired').catch((err) => err);
  check('公网地址换到了设备凭据', typeof paired?.token === 'string' && paired.token.length >= 32, paired?.message ?? '');
  deviceId = paired?.deviceId ?? null;

  const ok = await phone.wait('auth.ok').catch((err) => err);
  check('回答鉴权的是这台电脑上的代理', ok?.hostname === os.hostname(), `${ok?.hostname ?? ok?.message} / 期望 ${os.hostname()}`);

  // A real round trip: the relay must carry agent frames, not just greetings.
  phone.send({ type: 'chat.list' });
  const chats = await phone.wait('chats').catch((err) => err);
  check('一次真实请求穿过了中转', Array.isArray(chats?.chats), chats?.message ?? `chats=${chats?.chats?.length}`);

  const listed = await (await fetch(`${base}/devices.json`)).json();
  check('这台电脑的名单里能看到它', listed.devices.some((d) => d.id === deviceId && !d.revoked), JSON.stringify(listed.devices.map((d) => d.label)));
  check('名字是手机自己报的', listed.devices.some((d) => d.label === phoneName), phoneName);

  // Files are the one HTTP path that crosses the relay, with the same device
  // credential — worth proving here, because a phone that can chat but not fetch a
  // file would look like a bug in the file screen.
  const proof = `termdesk-relay-check ${Date.now()}`;
  const probe = `${os.homedir()}\\termdesk-relay-check.txt`;
  const relayHttp = String(pair.url).replace(/^wss:/, 'https:').replace(/^ws:/, 'http:');
  const upload = await fetch(`${relayHttp}/upload?path=${encodeURIComponent(probe)}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${paired.token}`, 'content-type': 'application/octet-stream' },
    body: proof,
  }).catch((err) => ({ ok: false, status: String(err?.message ?? err) }));
  check('上传可以穿过中转', upload.ok === true, `HTTP ${upload.status}`);
  const download = await fetch(`${relayHttp}/download?path=${encodeURIComponent(probe)}`, {
    headers: { Authorization: `Bearer ${paired.token}` },
  }).catch((err) => ({ ok: false, status: String(err?.message ?? err), text: async () => '' }));
  const text = await download.text();
  check('下载回来的是刚写进去的内容', download.ok === true && text === proof, `HTTP ${download.status} ${String(text).slice(0, 40)}`);
  check('没带凭据的文件请求被拒绝', (await fetch(`${relayHttp}/download?path=${encodeURIComponent(probe)}`)).status === 401);

  phone.send({ type: 'device.unpair' });
  const unpaired = await phone.wait('device.unpaired').catch((err) => err);
  check('手机能自己解绑', unpaired?.type === 'device.unpaired', unpaired?.message ?? '');
  phone.ws.close();
  await new Promise((r) => setTimeout(r, 400));
  const after = await (await fetch(`${base}/devices.json`)).json();
  check('名单里不再留着它', !after.devices.some((d) => d.id === deviceId));
} catch (err) {
  failures += 1;
  console.log(`  FAIL  ${err?.message ?? err}`);
} finally {
  try { phone?.ws.close(); } catch { /* already closed */ }
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
