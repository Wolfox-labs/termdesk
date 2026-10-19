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
import { judgeClientVersion } from './version.js';
import { connectionCandidates, describeCandidates } from './fallback.js';
import { loadOrCreateToken, tokenPath } from './auth.js';
import { allowedRoots } from './files.js';
import { handleTransferRequest } from './transfer.js';
import { handleLocalKernelRequest } from './localkernel.js';
import { TerminalManager } from './terminal.js';
import { listKernels, probeKernels } from './kernels/registry.js';
import { ChatManager } from './chat.js';
import { ChatContinuity, ownerForResponse } from './chat/continuity.js';
import { Notifier } from './notify.js';
import { loadRelayConfig, startRelayConnector } from './relay-client.js';
import { choosePairingUrl, devicesPage, pairPage, pairPayload, pairingUrlReason, qrMatrix, qrTerminal } from './pair.js';
import { createFrameHandler, pushStatusFrame } from './handlers.js';
import { reapOrphans, describeReap } from './spawnledger.js';
import { DeviceRegistry } from './devices.js';

/**
 * 兜底：任何未捕获异常都不许把整个服务端带走。
 *
 * 起因是一次真机测试：内核子进程的 stdin 关闭后，往它写会抛 `write EPIPE`，
 * 而这个错误没人监听——Node 的默认行为是**直接退出进程**。手机端看到的现象是
 * "内核跑了 5 步，然后既没有回复也没有产物"，因为服务端在那一刻已经死了。
 * 单个会话可以重来，但整台电脑的 agent 不该因为一根管道断掉就下线。
 * 这里只记录并继续；真正的修法是给每个子进程的 socket 挂 error 处理（见 kernels/*.js）。
 */
process.on('uncaughtException', (err) => {
  // 这个 try 不能省：日志本身也是往管道里写，管道断了会**在异常处理器里再抛一次**，
  // 那一样会让 Node 退出——第一次加固就栽在这里。
  try {
    console.log('  [guard] 未捕获异常，已记录并继续：' + (err && err.message ? err.message : String(err)));
  } catch {
    /* 连日志都写不出去时，唯一要做的就是别让进程死 */
  }
});
process.on('unhandledRejection', (err) => {
  try {
    console.log('  [guard] 未处理的 Promise 拒绝，已记录并继续：' + (err && err.message ? err.message : String(err)));
  } catch {
    /* 同上 */
  }
});

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
/**
 * What the phone gets told about while nobody is looking.
 *
 * Created before the chat manager because the manager is what reports into it — the
 * decision to notify belongs to the side that knows a turn ended, not to the phone.
 */
const notifier = new Notifier({ log: (line) => console.log(line) });
const chats = new ChatManager({ notifier });
const continuity = new ChatContinuity();

/** Authenticated connections share event sources, never each other's write authority. */
const connectedClients = new Map();
const chatFrameTypes = {
  'chat.event': S2C.CHAT_EVENT, 'chat.status': S2C.CHAT_STATUS,
  'chat.turn': S2C.CHAT_TURN, 'chat.closed': S2C.CHAT_CLOSED,
  'chat.approval': S2C.CHAT_APPROVAL, 'chat.terminals': S2C.CHAT_TERMINALS,
  'chat.terminal.output': S2C.CHAT_TERMINAL_OUTPUT, 'chat.terminal.input': S2C.CHAT_TERMINAL_INPUT,
};

function broadcast(type, payload, accepts = () => true) {
  let sent = 0;
  for (const [socket, client] of connectedClients) {
    if (socket.readyState !== socket.OPEN || !accepts(client)) continue;
    try {
      const body = type === S2C.CHATS ? {
        ...payload,
        chats: payload.chats.map((chat) => ({ ...chat, ownership: ownerForResponse(chat.id, continuity, client.identity) })),
      } : payload;
      client.send(type, body);
      sent += 1;
    } catch { /* One failed connection cannot interrupt other subscribers. */ }
  }
  return sent;
}

function attachStreams() {
  chats.attach(({ event, ...payload }) => {
    const terminalEvent = event.startsWith('chat.terminal');
    broadcast(chatFrameTypes[event] ?? S2C.CHAT_EVENT, payload,
      (client) => !terminalEvent || client.chatTerminals.has(payload.chatId));
    if (['chat.status', 'chat.turn', 'chat.closed'].includes(event)) pushChats();
  });
  terminals.attach((sessionId, text, stream) => {
    broadcast(S2C.TERM_OUTPUT, { sessionId, text, stream }, (client) => client.terminalId === sessionId);
  });
  // Desktop observations must not consume or replace the phone's notification path.
  notifier.attach((payload) => {
    if (!broadcast(S2C.NOTIFY, payload, (client) => client.identity.clientKind !== 'desktop')) {
      throw new Error('No notification subscriber');
    }
  });
}

function removeClient(socket) {
  if (!connectedClients.delete(socket)) return;
  chats.terminalWatch.clear();
  for (const client of connectedClients.values()) {
    for (const id of client.chatTerminals) chats.terminalWatch.add(id);
  }
  if (connectedClients.size === 0) {
    terminals.detach();
    chats.detach();
    notifier.detach();
  }
}

function disconnectCredential(credentialId) {
  for (const [socket, client] of connectedClients) {
    if (client.credentialId !== credentialId) continue;
    // Remove authority synchronously, before the asynchronous close handshake.
    removeClient(socket);
    client.send(S2C.AUTH_FAIL, { code: 'device_revoked', reason: 'device revoked' });
    socket.close(CLOSE_UNAUTHORIZED, 'device revoked');
  }
}

function parseArgs(argv) {
  const args = { port: DEFAULT_PORT, host: '0.0.0.0', showToken: false, local: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--show-token') args.showToken = true;
    else if (a === '--tunnel') throw new Error('Cloudflare 隧道已移除；请使用服务器中转或局域网连接。');
    else if (a === '--local') args.local = true;
    else if (a === '--port') args.port = Number(argv[++i]);
    else if (a === '--host') args.host = argv[++i];
  }
  // Local mode is the same agent, hosted by something that is not a desktop:
  // the phone's own sandbox. It is loopback-only by construction, so a tunnel,
  // a relay and a pairing page are not "disabled" - they are meaningless there.
  if (args.local) {
    args.host = '127.0.0.1';
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

/**
 * Pairing through the relay.
 *
 * Two things differ from the direct path. The address is the relay's, because the
 * phone may be anywhere; and what the phone gets is a one-time code, not this
 * machine's token — the token is the key to the agent itself and has no business
 * travelling to a phone that can only reach the relay anyway. The relay trades
 * the code for a credential of the phone's own, which is what makes "revoke this
 * one phone" possible without disturbing the others.
 */
async function relayPairing({ ttlMs } = {}) {
  if (!relayConnector) throw new Error('中转未启用');
  const name = os.hostname();
  const { code, expiresAt } = await relayConnector.pairingCode({ label: name, ttlMs });
  const wsUrl = relayConnector.url;
  // The relay is one address, and a phone holding only that one is dead when the
  // relay is. The fallbacks are this machine's own addresses — same Wi-Fi, or
  // Tailscale, which needs no Cloudflare — and they can only be handed over now.
  const more = connectionCandidates({
    primary: wsUrl,
    lanUrls: lanAddresses().map((addr) => `ws://${addr}:${args.port}`),
    port: args.port,
  }).slice(1);
  return { wsUrl, code, expiresAt, payload: pairPayload({ wsUrl, token: code, name, relay: true, more }) };
}

/** Why the last relay pairing attempt failed, so the page can say it. */
let relayPairError = null;

/** Direct pairing advertises a reachable LAN address only. */
function pairingUrl() {
  return choosePairingUrl({
    lanUrls: lanAddresses().map((addr) => `ws://${addr}:${args.port}`),
    port: args.port,
  });
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
function printHeader({ args, token, apk, relay, reap = null }) {
  const line = (label, value) => console.log('  ' + label.padEnd(7) + value);
  console.log('');
  // Said out loud at startup, before anything else can be blamed: kernels left behind
  // by a previous run are being killed, and that is why a process the person saw a
  // moment ago is gone.
  const reapLine = describeReap(reap ?? {});
  if (args.local) {
    console.log(`  TermDesk 本地内核 ${AGENT_VERSION} — 跑在这台机器自己的沙盒里`);
    console.log('  ' + '-'.repeat(64));
    line('监听', args.host + ':' + args.port + '（只在本机回环，外部连不上）');
    line('shell', SHELL_ENABLED ? '已开启（可以执行命令、读写文件）' : '已关闭（加 --enable-shell 打开）');
    line('目录', allowedRoots().join('   '));
    line('令牌', token.slice(0, 6) + '…   完整内容在 ' + tokenPath());
    line('公网', '不适用：本地内核不配对、不建隧道');
    if (reapLine) line('回收', reapLine);
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
    ? '已启用：' + relay.url + '（这台电脑主动拨出去，不需要域名和隧道）'
    : '未启用（局域网可直连；公网请配置 Relay 并设 TERMDESK_RELAY=1）');
  if (relay) {
    line('配对', 'http://127.0.0.1:' + args.port + '/pair   每次打开都会生成一个一次性的配对码');
    line('名单', 'http://127.0.0.1:' + args.port + '/devices   已配对的手机，可单独吊销');
    // Said out loud BEFORE it matters: "the relay is the only way in" is something
    // the person needs to know while the relay still works, not afterwards.
    line('回退', describeCandidates(connectionCandidates({
      primary: relay.url,
      lanUrls: lanAddresses().map((addr) => `ws://${addr}:${args.port}`),
      port: args.port,
    })));
  }
  if (reapLine) line('回收', reapLine);
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

/**
 * The phones this machine has paired locally.
 *
 * The master token still works (it is the legacy device), but a new pairing now
 * mints one secret per phone, so the console can rename or revoke exactly one
 * phone without invalidating every other pairing.
 */
const deviceRegistry = new DeviceRegistry({ masterToken: token });

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
  if (args.local && (url.pathname === '/app' || url.pathname === '/app.apk'
    || url.pathname.startsWith('/pair') || url.pathname.startsWith('/devices'))) {
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
      const scheme = 'http';
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
      connections: {
        lan: { supported: true, encrypted: false },
        relay: { configured: Boolean(relayConnector), connected: relayConnector?.connected ?? false, url: relayConnector?.url ?? null },
        p2p: { supported: false, reason: '尚未接入 ICE / DataChannel' },
      },
      apk: Boolean(findClientApk()),
      relay: Boolean(relayConnector),
    }));
    return;
  }

  /**
   * Who may connect to this computer, and the only place to take that away.
   *
   * Loopback only, like the pairing page. Every phone paired through the relay has
   * its own credential there, so revoking one is a real, narrow action instead of
   * "change the token and re-pair everything".
   */
  if (url.pathname === '/devices' || url.pathname === '/devices.json') {
    if (!isLoopback(req)) {
      res.writeHead(403, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'loopback_only', message: '这台电脑的手机名单只在本机可访问' }));
      return;
    }
    (async () => {
      const revoke = url.searchParams.get('revoke');
      const remove = url.searchParams.get('remove');
      let status = null;
      if (revoke) {
        try {
          status = (await deviceAdmin.revoke(revoke)).message;
        } catch (err) {
          status = '吊销失败：' + String(err?.message ?? err);
        }
      }
      if (remove) {
        try {
          status = (await deviceAdmin.remove(remove)).message;
        } catch (err) {
          status = '移除失败：' + String(err?.message ?? err);
        }
      }
      // The console wants one list. Local pairings live in this machine's own
      // registry; relay pairings live on the relay. `source` says which side can
      // revoke a row, so the UI never guesses.
      const local = deviceRegistry.list().map((d) => ({ ...d, source: 'local' }));
      const relay = relayConnector
        ? (await relayConnector.devices().catch(() => [])).map((d) => ({ ...d, source: 'relay' }))
        : [];
      const devices = [...local, ...relay];
      if (url.pathname === '/devices.json') {
        res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          ok: true,
          relay: Boolean(relayConnector),
          relayUrl: relayConnector?.url ?? null,
          hostname: os.hostname(),
          status,
          devices,
        }));
        return;
      }
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(devicesPage({
        devices,
        hostname: os.hostname(),
        relayUrl: relayConnector?.url ?? null,
        status,
      }));
    })().catch((err) => {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, code: 'devices_failed', message: String(err?.message ?? err) }));
      }
    });
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
      // Through the relay the QR carries a freshly minted one-time code instead of
      // this machine's token, and the relay is the address. When the relay cannot
      // answer, the direct path below still works — at home.
      if (relayConnector) {
        try {
          const pairing = await relayPairing();
          relayPairError = null;
          if (url.pathname === '/pair.json') {
            res.writeHead(200, { 'content-type': 'application/json' });
            res.end(JSON.stringify({
              ok: true,
              relay: true,
              url: pairing.wsUrl,
              code: pairing.code,
              token: pairing.code,
              expiresAt: pairing.expiresAt,
              payload: pairing.payload,
              hostname: os.hostname(),
              port: args.port,
              qr: await qrMatrix(pairing.payload),
            }));
            return;
          }
          const html = await pairPage({
            payload: pairing.payload,
            wsUrl: pairing.wsUrl,
            token: pairing.code,
            relay: { url: pairing.wsUrl, expiresAt: pairing.expiresAt },
            urlReason: 'relay',
            lanUrls: lanAddresses().map((a) => `ws://${a}:${args.port}`),
            // The install page is a plain HTTP file: the relay only forwards file
            // requests from phones that already paired, so this one is for home.
            appUrl: `http://${localAddresses()[0] ?? '127.0.0.1'}:${args.port}/app.apk`,
            expiresAt: Date.now(),
          });
          res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
          res.end(html);
          return;
        } catch (err) {
          relayPairError = String(err?.message ?? err);
        }
      }
      const wsUrl = await pairingUrl();
      // Why this address: the page and the JSON both say it, so "the scan went to
      // the wrong machine" is visible instead of mysterious.
      const urlReason = pairingUrlReason({
        lanUrls: localAddresses().map((a) => `ws://${a}:${args.port}`),
      });
      // Each scan mints one phone, not a copy of the machine key. The registry
      // record is what the console lists, renames and revokes later.
      const phone = deviceRegistry.register({ name: '待命名手机' });
      const payload = pairPayload({ wsUrl, token: phone.secret, name: os.hostname() });
      if (url.pathname === '/pair.json') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({
          ok: true,
          url: wsUrl,
          urlReason,
          relayError: relayPairError,
          token: phone.secret,
          deviceId: phone.id,
          payload,
          hostname: os.hostname(),
          port: args.port,
          // The desktop window draws this instead of an SVG.
          qr: await qrMatrix(payload),
        }));
        return;
      }
      // The phone must reach the install URL from its own network, so prefer the
      // public one; loopback would only work on the machine itself.
      const appUrl = `http://${localAddresses()[0] ?? '127.0.0.1'}:${args.port}/app.apk`;
      const html = await pairPage({
        payload,
        wsUrl,
        token: phone.secret,
        urlReason,
        relayError: relayPairError,
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
  const verifyCredential = (presented) => Boolean(deviceRegistry.verify(presented, { address: req.socket.remoteAddress }));
  const kernelHandled = handleLocalKernelRequest(req, res, url, verifyCredential);
  if (kernelHandled) return;

  handleTransferRequest(req, res, url, verifyCredential).then((handled) => {
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

/**
 * One facade over the two places a phone can live: this machine's registry and
 * the relay's list. Each result says which side owns the row, so a UI does not
 * offer "revoke" on a record the relay will ignore.
 */
const deviceAdmin = {
  async list() {
    const local = deviceRegistry.list().map((d) => ({ ...d, source: 'local' }));
    const relay = relayConnector
      ? (await relayConnector.devices().catch(() => [])).map((d) => ({ ...d, source: 'relay' }))
      : [];
    return [...local, ...relay];
  },

  async rename(id, name) {
    const trimmed = String(name ?? '').trim();
    if (!trimmed) return { ok: false, code: 'bad_name', message: '名称不能为空' };
    const renamed = deviceRegistry.rename(String(id ?? ''), trimmed);
    if (renamed) return { ok: true, code: 'renamed', message: `已重命名为 ${renamed.name}` };
    if (String(id).startsWith('d-')) return { ok: false, code: 'not_found', message: '这台电脑上没有这个设备' };
    return { ok: false, code: 'not_local', message: '只有本机登记的设备可以改名；中转设备的名称由中转维护' };
  },

  async revoke(id) {
    if (String(id) === 'legacy') {
      return { ok: false, code: 'legacy', message: '这是这台电脑的旧版令牌，不能单独吊销；更换令牌会让所有旧配对失效' };
    }
    const revoked = deviceRegistry.revoke(String(id ?? ''));
    if (revoked) {
      disconnectCredential(String(id));
      return { ok: true, code: 'revoked', message: `已吊销 ${revoked.name}，现有连接已断开` };
    }
    if (relayConnector) {
      try {
        await relayConnector.revoke(String(id ?? ''));
        return { ok: true, code: 'revoked', message: '已在中转上吊销这台手机' };
      } catch (err) {
        return { ok: false, code: 'relay_failed', message: String(err?.message ?? err) };
      }
    }
    return { ok: false, code: 'not_found', message: '没有这个设备' };
  },

  async remove(id) {
    if (String(id) === 'legacy') {
      return { ok: false, code: 'legacy', message: '旧版令牌的记录不能删除；它代表这台电脑的原始令牌' };
    }
    if (deviceRegistry.remove(String(id ?? ''))) {
      disconnectCredential(String(id));
      return { ok: true, code: 'removed', message: '已从本机名单删除' };
    }
    if (relayConnector) {
      try {
        await relayConnector.remove(String(id ?? ''));
        return { ok: true, code: 'removed', message: '已从中转名单删除' };
      } catch (err) {
        return { ok: false, code: 'relay_failed', message: String(err?.message ?? err) };
      }
    }
    return { ok: false, code: 'not_found', message: '没有这个设备' };
  },
};

const wss = new WebSocketServer({ server, maxPayload: MAX_FRAME_BYTES });

wss.on('connection', (socket, req) => {
  const peer = req.socket.remoteAddress;
  let authed = false;
  let statusTimer = null;
  const identity = { deviceId: '', deviceName: '', clientKind: '' };
  const client = { identity, credentialId: null, terminalId: null, chatTerminals: new Set(), send: null };

  // Second factor (P5-3): reject the upgrade before any frame is processed.
  const upgradeUrl = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  if (!accessKeyMatches(upgradeUrl.searchParams.get('access'))) {
    socket.close(CLOSE_UNAUTHORIZED, 'missing access key');
    return;
  }

  const send = (type, payload) => {
    if (type === S2C.TERM_OPENED) client.terminalId = payload.sessionId;
    if (type === S2C.CHAT_TERMINALS || type === S2C.CHAT_TERMINAL) client.chatTerminals.add(payload.chatId);
    if (socket.readyState === socket.OPEN) socket.send(encodeFrame(type, payload));
  };
  client.send = send;

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
    localMode: args.local,
    terminals,
    chats,
    continuity,
    identity,
    deviceAdmin,
    terminalOutputManaged: true,
    // So the phone can confirm what it received; unconfirmed entries are re-sent.
    notifier,
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
    if (authed && !connectedClients.has(socket)) {
      socket.close(CLOSE_UNAUTHORIZED, 'device revoked');
      return;
    }
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
      // One secret per phone (plus the legacy master token): the registry both
      // authenticates and says which device this is.
      const verified = deviceRegistry.verify(frame.token, { address: peer });
      if (!verified) {
        send(S2C.AUTH_FAIL, { reason: 'invalid token' });
        noteAuthFailure();
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'bad token');
        return;
      }
      authed = true;
      Object.assign(identity, {
        deviceId: verified.legacy && typeof frame.deviceId === 'string' && frame.deviceId.trim()
          ? frame.deviceId.trim()
          : (verified.id || 'legacy:' + peer),
        deviceName: typeof frame.deviceName === 'string' && frame.deviceName.trim() ? frame.deviceName.trim() : (frame.clientKind === 'android' ? '手机' : '桌面端'),
        clientKind: typeof frame.clientKind === 'string' && frame.clientKind.trim() ? frame.clientKind.trim() : 'legacy',
      });
      noteAuthSuccess();
      clearTimeout(authTimer);
      // The version check happens AFTER the credential: an unauthenticated peer
      // must not learn anything about this machine, including which protocol it
      // speaks. `auth.v` is new, and a client that omits it is read as v1 rather
      // than refused (see version.js).
      const compat = judgeClientVersion(frame.v);
      if (!compat.ok) {
        send(S2C.AUTH_FAIL, {
          reason: compat.reason,
          code: 'protocol_mismatch',
          clientV: compat.clientV,
          serverV: compat.serverV,
          minV: compat.minV,
        });
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'client protocol too old');
        return;
      }
      if (compat.upgrade === 'agent') {
        // Answered for an agent older than we can be sure of: say so in the log
        // rather than letting a newer app's feature fail silently later.
        console.log(`  客户端协议 v${compat.clientV} 比本机 v${compat.serverV} 新：按兼容处理`);
      }
      send(S2C.AUTH_OK, {
        hostname: os.hostname(),
        protocol: PROTOCOL_VERSION,
        // What we require of a client, so a phone can say which side to update.
        minV: compat.minV,
        agent: `termdesk-pc-agent/${AGENT_VERSION}`,
        capabilities: ['chat.sync', 'chat.ownership'],
      });
      send(S2C.HELLO, { hostname: os.hostname(), platform: `${os.platform()} ${os.release()}` });
      client.credentialId = verified.id;
      connectedClients.set(socket, client);
      if (connectedClients.size === 1) attachStreams();
      if (identity.clientKind !== 'desktop') {
        notifier.deliver((payload) => send(S2C.NOTIFY, payload));
      }
      return;
    }

    if (frame.type === C2S.NOTIFY_ACK && identity.clientKind === 'desktop') return;
    await handleFrame(frame);
    if ([C2S.CHAT_CREATE, C2S.CHAT_RESUME, C2S.CHAT_CLAIM, C2S.CHAT_RELEASE, C2S.CHAT_CLOSE].includes(frame.type)) pushChats();
  });

  socket.on('close', () => {
    clearTimeout(authTimer);
    stopStatus();
    removeClient(socket);
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
  broadcast(S2C.CHATS, {
    chats: chats.list(),
    approvals: chats.approvals.pending(),
  });
}

let relayConnector = null;
server.listen(args.port, args.host, async () => {
  // The launcher and management console enable the configured VPS relay.
  // CLI callers opt in explicitly; isolated tests can disable it with 0.
  const relayConfig = !args.local && process.env.TERMDESK_RELAY === '1' ? loadRelayConfig() : null;
  if (relayConfig) {
    relayConnector = startRelayConnector({
      config: relayConfig,
      port: args.port,
      token,
      accessKey: ACCESS_KEY,
      onDevicesChanged: () => { /* the list is read on demand from /devices */ },
      onReady: () => {
        // The relay is up, so a code can be minted: print one to scan now, and
        // keep /pair for later — every visit there mints a fresh one.
        relayPairing().then(async (pairing) => {
          console.log('  中转   ' + relayConfig.url + ' 已连接（' + relayConfig.nodeId + ' 在线）');
          console.log('  配对码 ' + pairing.code + '   10 分钟内有效，只能用一次');
          console.log('  配对页 http://127.0.0.1:' + args.port + '/pair   名单 http://127.0.0.1:' + args.port + '/devices');
          console.log('');
          console.log(await qrTerminal(pairing.payload).catch(() => ''));
        }).catch((err) => {
          console.error('  中转   出码失败：' + String(err?.message ?? err));
        });
      },
    });
  }
  printHeader({ args, token, apk: findClientApk(), relay: relayConfig, reap: await reapOrphans().catch(() => null) });
  // Discovery never starts a model turn.
  await printKernels().catch(() => {});

  if (!args.local && !relayConfig) {
    console.log('  连接   局域网直连；公网中转未配置，P2P 尚未接入');
    console.log('');
  }
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    relayConnector?.stop();
    terminals.disposeAll();
    chats.disposeAll();
    server.close(() => process.exit(0));
    // Do not hang forever waiting for sockets to drain.
    setTimeout(() => process.exit(0), 1500).unref();
  });
}
