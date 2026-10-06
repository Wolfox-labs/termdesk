#!/usr/bin/env node
/**
 * TermDesk PC agent.
 *
 * Serves a WebSocket endpoint that the Android client connects to: authenticate,
 * then stream host status and dispatch domain frames (processes, files,
 * terminal, AI). Frame dispatch lives in `handlers.js`; this file owns
 * connection lifecycle only.
 *
 * Usage:
 *   node src/server.js                  # listen on all interfaces, port 7420
 *   node src/server.js --show-token     # print the pairing token and exit
 *   node src/server.js --port 7420
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';

import { C2S, S2C, CLOSE_UNAUTHORIZED, parseFrame, encodeFrame, PROTOCOL_VERSION } from './protocol.js';
import { loadOrCreateToken, tokenMatches, tokenPath } from './auth.js';
import { allowedRoots } from './files.js';
import { handleTransferRequest } from './transfer.js';
import { handleLocalKernelRequest } from './localkernel.js';
import { TerminalManager } from './terminal.js';
import { listKernels, probeKernels } from './kernels/registry.js';
import { ChatManager } from './chat.js';
import { loadRelayConfig, startRelayConnector } from './relay-client.js';
import { Tunnel, findCloudflared, findTunnelConfig, verifyPublic } from './tunnel.js';
import { pairPage, pairPayload, qrMatrix, qrTerminal } from './pair.js';
import { createFrameHandler, pushStatusFrame } from './handlers.js';

const DEFAULT_PORT = 7420;
/** Cap inbound frames: file writes are the largest legitimate payload (~2 MB text). */
const MAX_FRAME_BYTES = 8 * 1024 * 1024;

/**
 * What this build is.
 *
 * Read from package.json instead of typed here again: the phone, the desktop
 * window and this agent have to agree on one number for a version to mean
 * anything. It drifted once already — the status route said 0.2.0 while the
 * handshake said 0.1.0 — and a frozen version that lies about itself is worse
 * than no version at all.
 */
const AGENT_VERSION = JSON.parse(
  fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'),
).version;

/**
 * Arbitrary shell access is the most powerful thing this agent can expose, so
 * it is off unless explicitly enabled. Turn it on with `--enable-shell` or
 * TERMDESK_ENABLE_SHELL=1.
 */
const SHELL_ENABLED =
  process.argv.includes('--enable-shell') || process.env.TERMDESK_ENABLE_SHELL === '1';

/**
 * Auth failure throttle per remote address.
 *
 * The pairing token is 32 random bytes, so online brute force is not a real
 * threat — but a noisy scanner should not be able to spin the auth path as
 * fast as it likes. After `AUTH_FAIL_THRESHOLD` consecutive failures further
 * auth attempts from that address are refused for `AUTH_LOCKOUT_MS`.
 */
const AUTH_FAIL_THRESHOLD = 8;
const AUTH_LOCKOUT_MS = 30_000;
const authFailures = new Map(); // peer -> { count, lockedUntil }

/**
 * Optional second factor for public exposure (P5-3).
 *
 * The pairing token already gates every privileged call. When the agent is
 * reachable over a public tunnel, that single secret is the whole blast radius.
 * Set TERMDESK_ACCESS_KEY to also require `X-TermDesk-Key` on HTTP and
 * `?access=` on the WebSocket upgrade — the key never travels in a frame body
 * and is never logged. Leave it unset for LAN / Tailscale use.
 */
const ACCESS_KEY = process.env.TERMDESK_ACCESS_KEY || '';

function accessKeyMatches(presented) {
  if (!ACCESS_KEY) return true;
  if (typeof presented !== 'string' || presented.length === 0) return false;
  const a = Buffer.from(ACCESS_KEY, 'utf8');
  const b = Buffer.from(presented, 'utf8');
  if (a.length !== b.length) {
    crypto.timingSafeEqual(a, a);
    return false;
  }
  return crypto.timingSafeEqual(a, b);
}

function httpAccessKey(req, url) {
  return req.headers['x-termdesk-key'] ?? url.searchParams.get('access');
}

const terminals = new TerminalManager();
const chats = new ChatManager();

/**
 * The socket currently receiving streamed terminal/engine/chat frames.
 *
 * Only one client is routed at a time ("last authenticated client wins"), and a
 * socket may only detach routing if it is still that client. Without this
 * check, any connection that closes — a rejected token, a port scan, a phone
 * reconnecting — would silently tear down routing for the healthy client and
 * its live output would stop arriving with no error anywhere.
 */
let routedSocket = null;

function parseArgs(argv) {
  const args = { port: DEFAULT_PORT, host: '0.0.0.0', showToken: false, tunnel: false, local: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--show-token') args.showToken = true;
    else if (a === '--tunnel') args.tunnel = true;
    else if (a === '--local') args.local = true;
    else if (a === '--port') args.port = Number(argv[++i]);
    else if (a === '--host') args.host = argv[++i];
  }
  // Local mode is the same agent, hosted by something that is not a desktop:
  // the phone's own sandbox. It is loopback-only by construction, so a tunnel,
  // a relay and a pairing page are not "disabled" - they are meaningless there.
  if (args.local) {
    args.host = '127.0.0.1';
    args.tunnel = false;
  }
  return args;
}

/**
 * Is this request from the machine itself?
 *
 * The pairing page carries the token, so it is served on loopback only: a page
 * that leaked the token through the tunnel would defeat the tunnel's auth.
 */
function isLoopback(req) {
  const peer = req.socket?.remoteAddress ?? '';
  return peer === '127.0.0.1' || peer === '::1' || peer === '::ffff:127.0.0.1';
}

const tunnel = new Tunnel();
/** In-flight tunnel start, so repeated /pair hits do not spawn several. */
let tunnelStart = null;

function ensureTunnel() {
  // Local mode is loopback-only by construction: there is no public address to
  // hand out, and cloudflared started inside a phone sandbox would be useless and
  // invisible. Refusing here means no future route can reintroduce it.
  if (args.local) return Promise.resolve(tunnel.status());
  if (tunnel.status().running) return Promise.resolve(tunnel.status());
  if (tunnelStart === null) {
    tunnelStart = tunnel.start({ port: args.port }).finally(() => { tunnelStart = null; });
  }
  return tunnelStart;
}

/** The address the phone should use: the tunnel when up, else loopback. */
function pairingUrl() {
  if (tunnel.url) return `wss://${tunnel.url.replace(/^https:\/\//, '')}`;
  return `ws://127.0.0.1:${args.port}`;
}

/**
 * The built Android APK, newest first.
 *
 * Serving it lets a phone install or upgrade itself with no cable: the agent is
 * already reachable from the phone (LAN or tunnel), so "open this URL on the
 * phone" replaces "plug it in and run adb". Not a secret — it is the app the
 * user is about to run.
 */
function findClientApk() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const candidates = [
    path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
  ];
  let newest = null;
  for (const candidate of candidates) {
    try {
      const stat = fs.statSync(candidate);
      if (stat.isFile() && (newest === null || stat.mtimeMs > newest.mtimeMs)) {
        newest = { path: candidate, size: stat.size, mtimeMs: stat.mtimeMs };
      }
    } catch {
      // not built yet
    }
  }
  return newest;
}

/**
 * Addresses worth showing a phone, best first.
 *
 * Home/office ranges come first, then Tailscale/CGNAT, then whatever is left
 * (virtual switches and VPN adapters are usually noise from a phone's point of
 * view). Capped so the header stays a header.
 */
function lanAddresses(limit = 4) {
  const score = (addr) => {
    if (/^192\.168\./.test(addr)) return 0;
    if (/^10\./.test(addr)) return 1;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(addr)) return 2;
    if (/^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(addr)) return 3;
    return 4;
  };
  return localAddresses().sort((a, b) => score(a) - score(b)).slice(0, limit);
}

/** Every non-internal IPv4 address, so we can print usable URLs. */
function localAddresses() {
  const out = [];
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos ?? []) {
      if (info.family === 'IPv4' && !info.internal) out.push(info.address);
    }
  }
  return out;
}

/**
 * Startup report helpers.
 *
 * A manual launch should answer the only three questions that matter before the
 * phone is picked up: is the agent listening, what can this PC actually drive,
 * and what address does the phone use. All of it is printed here instead of
 * being discovered later on a phone screen.
 */

/** Where the agent is listening, and how to pair. */
function printHeader({ args, token, apk, relay }) {
  const line = (label, value) => console.log('  ' + label.padEnd(7) + value);
  console.log('');
  if (args.local) {
    console.log(`  TermDesk 本地内核 ${AGENT_VERSION} — 跑在这台机器自己的沙盒里`);
    console.log('  ' + '-'.repeat(64));
    line('监听', args.host + ':' + args.port + '（只在本机回环，外部连不上）');
    line('shell', SHELL_ENABLED ? '已开启（可以执行命令、读写文件）' : '已关闭（加 --enable-shell 打开）');
    line('目录', allowedRoots().join('   '));
    line('令牌', token.slice(0, 6) + '…   完整内容在 ' + tokenPath());
    line('公网', '不适用：本地内核不配对、不建隧道');
    console.log('');
    return;
  }
  console.log(`  TermDesk PC ${AGENT_VERSION} — 手机远程指挥这台电脑上的 agent 内核`);
  console.log('  ' + '-'.repeat(64));
  line('本机', os.hostname() + '   监听 ' + args.host + ':' + args.port);
  line('shell', SHELL_ENABLED ? '已开启（手机可执行任意命令、读写文件）' : '已关闭（加 --enable-shell 打开）');
  line('目录', allowedRoots().join('   '));
  // One machine can carry a dozen IPv4s (WSL, VPNs, virtual switches). Only the
  // first few LAN-shaped ones are worth printing; the phone only needs one.
  for (const addr of lanAddresses()) line('局域网', 'ws://' + addr + ':' + args.port);
  line('配对页', 'http://127.0.0.1:' + args.port + '/pair   在这台电脑上打开，用手机扫码');
  if (apk) line('安装页', 'http://127.0.0.1:' + args.port + '/app    手机还没装 App 时打开它');
  line('令牌', token.slice(0, 6) + '…   完整内容在 ' + tokenPath() + '（等于这台电脑的钥匙，不要外发）');
  line('中转', relay
    ? '已启用 VPS 中转'
    : '未启用（内网或 Cloudflare 隧道即可；需要时设 TERMDESK_RELAY=1）');
  console.log('');
}

/** The kernel table: what can be driven today, and honestly why not the rest. */
async function printKernels() {
  // Live ACP handshakes only when asked for: a startup banner must not spawn.
  const list = await probeKernels();
  console.log('  内核');
  for (const kernel of list) {
    const mark = kernel.selectable ? '✔' : (kernel.available ? '○' : '·');
    console.log('   ' + mark + ' ' + String(kernel.label).padEnd(18) + String(kernel.tier).padEnd(12) + kernel.detail);
  }
  const usable = list.filter((kernel) => kernel.selectable).map((kernel) => kernel.label);
  console.log('   手机上可选：' + (usable.join(' / ') || '（无）'));
  console.log('');
}

/** When this process started, reported by /status.json. */
const startedAt = Date.now();

const args = parseArgs(process.argv.slice(2));
const token = loadOrCreateToken();

if (args.showToken) {
  console.log(token);
  console.log(`(also stored at ${tokenPath()})`);
  process.exit(0);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

  // Local mode has no desktop-only surfaces. Checked here, before any route,
  // not inside one of them: the pairing page stayed reachable - and even started
  // a tunnel - because the guard sat inside the APK block.
  if (args.local && (url.pathname === '/app' || url.pathname === '/app.apk' || url.pathname.startsWith('/pair'))) {
    res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      ok: false,
      message: '本地内核不提供配对页：这个代理只在本机回环上，由手机里的 TermDesk 直接启动',
    }));
    return;
  }

  // A tiny health endpoint makes it easy to confirm the agent is reachable
  // from the phone's browser before pairing the app. When an access key is
  // configured it is gated too — a public tunnel should not advertise itself.
  if (url.pathname === '/healthz') {
    if (!accessKeyMatches(httpAccessKey(req, url))) {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'unauthorized' }));
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'termdesk-pc-agent', version: AGENT_VERSION, protocol: PROTOCOL_VERSION }));
    return;
  }

  // The phone installs itself from here. Checked before the access key on
  // purpose: a phone that has no app yet cannot present a key, and shipping the
  // client binary is not what the key protects.
  if (url.pathname === '/app.apk' || url.pathname === '/app') {
    const apk = findClientApk();
    if (apk === null) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('还没有构建 APK：cd android && ./gradlew :app:assembleDebug\n');
      return;
    }
    if (url.pathname === '/app' && !/apk|android/i.test(req.headers.accept ?? '')) {
      // A browser gets told what this is; a downloader gets the file.
      const host = req.headers.host ?? `127.0.0.1:${args.port}`;
      const scheme = tunnel.url ? 'https' : 'http';
      const page = `<!doctype html><meta charset="utf-8"><title>TermDesk App</title>
<body style="font-family:system-ui;background:#272822;color:#f8f8f2;padding:28px;max-width:560px">
<h1 style="font-size:19px">安装 TermDesk 手机端</h1>
<p style="color:#a6a28c;font-size:14px;line-height:1.7">下载并安装下面的 APK（同签名的旧版本会原地升级，配对信息保留）。</p>
<p><a style="color:#66d9ef;font-size:16px" href="${scheme}://${host}/app.apk">下载 APK（${Math.round(apk.size / 1024 / 1024)} MB）</a></p>
<p style="color:#a6a28c;font-size:13px;line-height:1.7">装好后回到电脑上打开配对页扫码：<br>http://127.0.0.1:${args.port}/pair</p>
</body>`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(page);
      return;
    }
    res.writeHead(200, {
      'content-type': 'application/vnd.android.package-archive',
      'content-length': String(apk.size),
      'content-disposition': 'attachment; filename="termdesk.apk"',
    });
    fs.createReadStream(apk.path).pipe(res);
    return;
  }

  /**
   * DEVELOPMENT ONLY: raise a synthetic approval question.
   *
   * The phone's approval dialog can otherwise only be reached by making an engine
   * want to run something, which costs a real model call. This hook lets the
   * whole path be exercised for free — broker, frame, phone, answer, transcript —
   * against the real code, and it is the only reason it exists.
   *
   * Off unless TERMDESK_DEBUG_APPROVAL=1, loopback-only like the other JSON
   * endpoints, and it never answers a question that a real kernel asked.
   */
  if (url.pathname === '/debug/approval' && process.env.TERMDESK_DEBUG_APPROVAL === '1') {
    if (!isLoopback(req)) {
      res.writeHead(403, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'loopback_only' }));
      return;
    }
    (async () => {
      const engine = url.searchParams.get('engine') || 'opencode';
      const created = chats.create({ engine, cwd: os.homedir(), title: '审批测试' });
      if (!created.ok) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, message: created.message }));
        return;
      }
      const chatId = created.chat.id;
      // The phone is not the one that created this chat, so it has to be told.
      pushChats();
      const optionId = await chats.approvals.request({
        chatId,
        engine,
        title: '内核想要执行 npm test',
        detail: '',
        kind: 'command',
        fallback: 'deny',
      });
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: true, chatId, optionId }));
    })().catch((err) => {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, message: String(err?.message ?? err) }));
      }
    });
    return;
  }

  /**
   * Machine-readable state for the desktop window.
   *
   * Loopback only, like the pairing page: these describe this computer, so the
   * tunnel must not become a way to enumerate it. They exist because the desktop
   * shell is a client of the same agent the phone talks to — one source of
   * truth, instead of the GUI re-deriving anything.
   */
  if (url.pathname === '/status.json' || url.pathname === '/kernels.json') {
    if (!isLoopback(req)) {
      res.writeHead(403, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'loopback_only' }));
      return;
    }
    if (url.pathname === '/kernels.json') {
      probeKernels()
        .then((kernels) => {
          res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ ok: true, kernels }));
        })
        .catch((err) => {
          res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ ok: false, message: String(err?.message ?? err) }));
        });
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      ok: true,
      service: 'termdesk-pc-agent',
      version: AGENT_VERSION,
      protocol: PROTOCOL_VERSION,
      hostname: os.hostname(),
      platform: `${os.platform()} ${os.release()}`,
      port: args.port,
      host: args.host,
      shell: SHELL_ENABLED,
      roots: allowedRoots(),
      lanUrls: lanAddresses().map((a) => `ws://${a}:${args.port}`),
      startedAt: startedAt,
      uptimeMs: Date.now() - startedAt,
      tunnel: tunnel.status(),
      apk: Boolean(findClientApk()),
      relay: Boolean(relayConnector),
    }));
    return;
  }

  // Pairing: loopback only, because the page contains the token.
  if (url.pathname === '/pair' || url.pathname === '/pair.json') {
    if (!isLoopback(req)) {
      res.writeHead(403, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'loopback_only', message: '配对页面只在本机可访问' }));
      return;
    }
    const respond = async () => {
      let status = tunnel.status();
      if (!status.running) status = await ensureTunnel().catch(() => tunnel.status());
      const wsUrl = pairingUrl();
      const payload = pairPayload({ wsUrl, token, name: os.hostname() });
      if (url.pathname === '/pair.json') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({
          ok: true,
          url: wsUrl,
          token,
          payload,
          tunnel: status,
          cloudflared: findCloudflared(),
          hostname: os.hostname(),
          port: args.port,
          // The desktop window draws this instead of an SVG.
          qr: await qrMatrix(payload),
        }));
        return;
      }
      // The phone must reach the install URL from its own network, so prefer the
      // public one; loopback would only work on the machine itself.
      const appUrl = status.url
        ? `${status.url}/app.apk`
        : `http://${localAddresses()[0] ?? '127.0.0.1'}:${args.port}/app.apk`;
      const html = await pairPage({
        payload,
        wsUrl,
        token,
        tunnel: status,
        lanUrls: localAddresses().map((a) => `ws://${a}:${args.port}`),
        appUrl,
        expiresAt: Date.now(),
      });
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(html);
    };
    respond().catch((err) => {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ ok: false, code: 'pair_failed', message: String(err?.message ?? err) }));
      }
    });
    return;
  }

  // File transfers ride the same port but use bearer auth and streaming.
  if (!accessKeyMatches(httpAccessKey(req, url))) {
    res.writeHead(401, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, code: 'unauthorized', message: 'missing or wrong access key' }));
    return;
  }
  // The local kernel payload is a transfer too: same token, same surface.
  const kernelHandled = handleLocalKernelRequest(req, res, url, token);
  if (kernelHandled) return;

  handleTransferRequest(req, res, url, token).then((handled) => {
    if (!handled) {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not found\n');
    }
  }).catch((err) => {
    if (!res.headersSent) {
      res.writeHead(500, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'internal', message: String(err?.message ?? err) }));
    }
  });
});

const wss = new WebSocketServer({ server, maxPayload: MAX_FRAME_BYTES });

wss.on('connection', (socket, req) => {
  const peer = req.socket.remoteAddress;
  let authed = false;
  let statusTimer = null;

  // Second factor (P5-3): reject the upgrade before any frame is processed.
  const upgradeUrl = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  if (!accessKeyMatches(upgradeUrl.searchParams.get('access'))) {
    socket.close(CLOSE_UNAUTHORIZED, 'missing access key');
    return;
  }

  const send = (type, payload) => {
    if (socket.readyState === socket.OPEN) socket.send(encodeFrame(type, payload));
  };

  const stopStatus = () => {
    if (statusTimer !== null) {
      clearInterval(statusTimer);
      statusTimer = null;
    }
  };

  const startStatusTimer = (ms) => {
    statusTimer = setInterval(() => { void pushStatusFrame(send); }, ms);
  };

  const pushStatus = async () => {
    await pushStatusFrame(send);
  };

  const handleFrame = createFrameHandler({
    send,
    socket,
    shellEnabled: SHELL_ENABLED,
    terminals,
    chats,
    pushStatus,
    stopStatus,
    startStatusTimer,
  });

  const noteAuthFailure = () => {
    const now = Date.now();
    const rec = authFailures.get(peer) ?? { count: 0, lockedUntil: 0 };
    rec.count += 1;
    if (rec.count >= AUTH_FAIL_THRESHOLD) {
      rec.lockedUntil = now + AUTH_LOCKOUT_MS;
      rec.count = 0;
    }
    authFailures.set(peer, rec);
  };

  const noteAuthSuccess = () => {
    authFailures.delete(peer);
  };

  const authLocked = () => {
    const rec = authFailures.get(peer);
    return rec !== undefined && rec.lockedUntil > Date.now();
  };

  const authTimer = setTimeout(() => {
    if (!authed) {
      socket.close(CLOSE_UNAUTHORIZED, 'auth timeout');
    }
  }, 10_000);

  socket.on('message', async (raw, isBinary) => {
    if (isBinary) {
      send(S2C.ERROR, { code: 'unsupported', message: 'binary frames are not supported' });
      return;
    }
    const parsed = parseFrame(raw.toString());
    if (!parsed.ok) {
      send(S2C.ERROR, { code: 'bad_frame', message: parsed.error });
      // An unauthenticated peer that cannot even speak the protocol gets no
      // grace period: close immediately instead of waiting out the timer.
      if (!authed) {
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'bad frame before auth');
      }
      return;
    }
    const frame = parsed.value;

    if (!authed) {
      if (authLocked()) {
        send(S2C.AUTH_FAIL, { reason: 'too many failed attempts' });
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'auth locked out');
        return;
      }
      if (frame.type !== C2S.AUTH) {
        send(S2C.AUTH_FAIL, { reason: 'first frame must be auth' });
        noteAuthFailure();
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'unauthenticated');
        return;
      }
      if (!tokenMatches(token, frame.token)) {
        send(S2C.AUTH_FAIL, { reason: 'invalid token' });
        noteAuthFailure();
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'bad token');
        return;
      }
      authed = true;
      noteAuthSuccess();
      clearTimeout(authTimer);
      send(S2C.AUTH_OK, {
        hostname: os.hostname(),
        protocol: PROTOCOL_VERSION,
        agent: `termdesk-pc-agent/${AGENT_VERSION}`,
      });
      send(S2C.HELLO, { hostname: os.hostname(), platform: `${os.platform()} ${os.release()}` });
      routedSocket = socket;
      // Chat frames carry their own type names: a chat has four distinct
      // stream kinds (event/status/turn/closed).
      chats.attach((payload) => {
        if (socket.readyState !== socket.OPEN) return;
        const { event, ...rest } = payload;
        const type = S2C[{
          'chat.event': 'CHAT_EVENT',
          'chat.status': 'CHAT_STATUS',
          'chat.turn': 'CHAT_TURN',
          'chat.closed': 'CHAT_CLOSED',
          'chat.approval': 'CHAT_APPROVAL',
          // A conversation's command lines stream under their own names, so the
          // phone can tell a terminal that changed apart from a transcript item.
          'chat.terminals': 'CHAT_TERMINALS',
          'chat.terminal.output': 'CHAT_TERMINAL_OUTPUT',
          'chat.terminal.input': 'CHAT_TERMINAL_INPUT',
        }[event]] ?? S2C.CHAT_EVENT;
        socket.send(encodeFrame(type, rest));
      });
      return;
    }

    await handleFrame(frame);
  });

  socket.on('close', () => {
    clearTimeout(authTimer);
    stopStatus();
    // Only the client that currently owns routing may release it. A rejected or
    // superseded socket must not detach the live client's streams.
    if (routedSocket === socket) {
      routedSocket = null;
      // Stop routing output to a socket that no longer exists; the sessions
      // themselves stay alive so a reconnect keeps its scrollback.
      terminals.detach();
      chats.detach();
    }
  });

  socket.on('error', (err) => {
    console.error(`[termdesk] socket error from ${peer}: ${err?.message ?? err}`);
  });
});

/**
 * A startup failure has to be actionable, not a stack trace.
 *
 * The realistic conflict is another TermDesk on this port: this machine's own
 * scheduled task starts an instance at logon, and a second one started by hand
 * would otherwise die with a bare EADDRINUSE. Say what is holding the port and
 * how to free it.
 */
function explainListenFailure(err) {
  if (err?.code === 'EADDRINUSE') {
    console.error('');
    console.error(`  端口 ${args.port} 已被占用，无法启动。`);
    console.error('  最常见的原因：这台电脑上已经有一个 TermDesk 在跑');
    console.error('  （计划任务 "TermDesk Agent" 会在登录时自启一个只监听本机 127.0.0.1 的旧实例）。');
    console.error('');
    console.error('  先停掉它，再启动本次的实例：');
    console.error('    Stop-ScheduledTask -TaskName "TermDesk Agent"');
    console.error('');
    console.error(`  或者换个端口：TermDesk.bat --port 7421   （注意 Cloudflare ingress 指向的是 ${args.port}）`);
    console.error('');
    return;
  }
  if (err?.code === 'EACCES') {
    console.error(`  没有权限监听 ${args.host}:${args.port}（换端口，或不要绑定 0.0.0.0）`);
    return;
  }
  console.error(`  服务启动失败：${err?.message ?? err}`);
}

const onListenError = (err) => {
  explainListenFailure(err);
  process.exit(1);
};
// Both registrations are needed: the HTTP server reports EADDRINUSE, and the
// WebSocketServer built from it re-emits the same error — that second one,
// unhandled, is a crash before any message can be printed.
server.on('error', onListenError);
wss.on('error', onListenError);

/**
 * Tell the connected client which conversations exist right now.
 *
 * Handlers answer a chat.* request with the list, but a conversation can also
 * appear without the phone asking: the desktop window creates one, and the
 * development hook below does too. Without this the phone keeps showing an empty
 * list while a chat exists on the PC.
 */
function pushChats() {
  if (!routedSocket || routedSocket.readyState !== routedSocket.OPEN) return;
  routedSocket.send(encodeFrame(S2C.CHATS, {
    chats: chats.list(),
    approvals: chats.approvals.pending(),
  }));
}

let relayConnector = null;
server.listen(args.port, args.host, async () => {
  // Opt-in on purpose. The relay is a second, older path to the same agent and
  // it depends on a local proxy being up; with a working Cloudflare tunnel it
  // only added a reconnect loop nobody could explain. TERMDESK_RELAY=1 turns it
  // back on.
  const relayConfig = !args.local && process.env.TERMDESK_RELAY === '1' ? loadRelayConfig() : null;
  if (relayConfig) relayConnector = startRelayConnector({ config: relayConfig, port: args.port, token, accessKey: ACCESS_KEY });
  printHeader({ args, token, apk: findClientApk(), relay: Boolean(relayConfig) });
  // Printed before the tunnel on purpose: the kernel list is useful immediately,
  // while cloudflared may still be negotiating its connections.
  await printKernels().catch(() => {});

  // A public address is the point of the tunnel: with it the phone works on
  // mobile data, on a friend's Wi-Fi, anywhere — no Tailscale and no port
  // forwarding. Starting it is explicit (--tunnel) so the machine is not put on
  // the internet merely by running the agent.
  if (args.tunnel) {
    const configFile = findTunnelConfig();
    console.log('  公网   正在启动 cloudflared…' + (configFile ? '（使用已有配置 ' + configFile + '）' : '（临时地址模式）'));
    try {
      const status = await tunnel.start({ port: args.port });
      const wsUrl = 'wss://' + status.url.replace(/^https:\/\//, '');
      console.log('  公网   ' + status.url + '   ' + (status.stable ? '固定域名' : '临时地址（重启会变）'));
      // Registration is not proof: DNS route + ingress rule + proxy must all be
      // right. One public request settles it before any QR code is printed.
      const verdict = await verifyPublic({ url: status.url });
      console.log(verdict.ok
        ? '  自检   公网地址已验证可以访问'
        : '  自检   公网地址暂时不可用：' + (verdict.error ?? verdict.status));
      console.log('');
      console.log('  手机扫码配对（也可在 App 里手动填上面的 wss 地址 + 令牌）');
      console.log(await qrTerminal(pairPayload({ wsUrl, token, name: os.hostname() })));
    } catch (err) {
      console.error('  公网   启动失败：' + (err?.message ?? err));
      console.error('  公网   ' + (findCloudflared() ? '检查网络后重试' : '把 cloudflared 放进 tools/ 或设置 TERMDESK_CLOUDFLARED'));
    }
  } else if (!args.local) {
    // No tunnel line in local mode: the banner there already said 不适用.
    console.log('  公网   未启动（加 --tunnel，或直接双击 TermDesk.bat）');
    console.log('');
  }
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    relayConnector?.stop();
    tunnel.stop();
    terminals.disposeAll();
    chats.disposeAll();
    server.close(() => process.exit(0));
    // Do not hang forever waiting for sockets to drain.
    setTimeout(() => process.exit(0), 1500).unref();
  });
}
