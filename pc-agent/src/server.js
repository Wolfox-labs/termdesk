#!/usr/bin/env node
/**
 * TermDesk PC agent — P0 skeleton.
 *
 * Serves a WebSocket endpoint that the Android client connects to. In P0 the
 * agent only does two things: authenticate the client, and stream live host
 * status. Terminal, files, and AI engines arrive in later phases.
 *
 * Usage:
 *   node src/server.js                  # listen on all interfaces, port 7420
 *   node src/server.js --show-token     # print the pairing token and exit
 *   node src/server.js --port 7420
 */
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { WebSocketServer } from 'ws';

import { C2S, S2C, CLOSE_UNAUTHORIZED, parseFrame, encodeFrame, PROTOCOL_VERSION } from './protocol.js';
import { loadOrCreateToken, tokenMatches, tokenPath } from './auth.js';
import { collectStatus } from './system.js';
import { listProcesses, listServices, matchesQuery, invalidateInventory } from './inventory.js';
import { killProcess, controlService } from './actions.js';
import {
  allowedRoots,
  createEntry,
  deleteEntry,
  listDirectory,
  readTextFile,
  renameEntry,
  writeTextFile,
} from './files.js';
import { handleTransferRequest } from './transfer.js';
import { TerminalManager } from './terminal.js';
import { EngineManager } from './engines.js';
import {
  applyProviderConfig,
  codepaths,
  readCodexConfig,
  restoreBackup,
} from './codexconfig.js';
import { listSessions, readSession, sessionRoots } from './sessions.js';
import { ChatManager } from './chat.js';

const DEFAULT_PORT = 7420;
const STATUS_INTERVAL_MS = 2000;

/**
 * Arbitrary shell access is the most powerful thing this agent can expose, so
 * it is off unless explicitly enabled. Turn it on with `--enable-shell` or
 * TERMDESK_ENABLE_SHELL=1.
 */
const SHELL_ENABLED =
  process.argv.includes('--enable-shell') || process.env.TERMDESK_ENABLE_SHELL === '1';

const terminals = new TerminalManager();
const engines = new EngineManager();
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
  const args = { port: DEFAULT_PORT, host: '0.0.0.0', showToken: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--show-token') args.showToken = true;
    else if (a === '--port') args.port = Number(argv[++i]);
    else if (a === '--host') args.host = argv[++i];
  }
  return args;
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

const args = parseArgs(process.argv.slice(2));
const token = loadOrCreateToken();

if (args.showToken) {
  console.log(token);
  console.log(`(also stored at ${tokenPath()})`);
  process.exit(0);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

  // A tiny health endpoint makes it easy to confirm the agent is reachable
  // from the phone's browser before pairing the app.
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'termdesk-pc-agent', protocol: PROTOCOL_VERSION }));
    return;
  }

  // File transfers ride the same port but use bearer auth and streaming.
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

const wss = new WebSocketServer({ server });

wss.on('connection', (socket, req) => {
  const peer = req.socket.remoteAddress;
  let authed = false;
  let statusTimer = null;

  const send = (type, payload) => {
    if (socket.readyState === socket.OPEN) socket.send(encodeFrame(type, payload));
  };

  const stopStatus = () => {
    if (statusTimer !== null) {
      clearInterval(statusTimer);
      statusTimer = null;
    }
  };

  const pushStatus = async () => {
    try {
      send(S2C.STATUS, { status: await collectStatus() });
    } catch (err) {
      send(S2C.ERROR, { code: 'status_failed', message: String(err?.message ?? err) });
    }
  };

  const authTimer = setTimeout(() => {
    if (!authed) {
      socket.close(CLOSE_UNAUTHORIZED, 'auth timeout');
    }
  }, 10_000);

  socket.on('message', async (raw, isBinary) => {
    if (isBinary) {
      send(S2C.ERROR, { code: 'unsupported', message: 'binary frames are not supported in P0' });
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
      if (frame.type !== C2S.AUTH) {
        send(S2C.AUTH_FAIL, { reason: 'first frame must be auth' });
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'unauthenticated');
        return;
      }
      if (!tokenMatches(token, frame.token)) {
        send(S2C.AUTH_FAIL, { reason: 'invalid token' });
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, 'bad token');
        return;
      }
      authed = true;
      clearTimeout(authTimer);
      send(S2C.AUTH_OK, {
        hostname: os.hostname(),
        protocol: PROTOCOL_VERSION,
        agent: 'termdesk-pc-agent/0.1.0',
      });
      send(S2C.HELLO, { hostname: os.hostname(), platform: `${os.platform()} ${os.release()}` });
      routedSocket = socket;
      // Route engine task events to this socket. Last authenticated client wins,
      // which matches how terminal output is routed.
      engines.attach((payload) => {
        if (socket.readyState !== socket.OPEN) return;
        // The engine manager reports its own event name in `event`. It must NOT
        // use `type`, because encodeFrame spreads the payload after its own
        // `type` and a colliding key silently replaced the wire frame type.
        const type = payload.event === 'task.started'
          ? S2C.AI_STARTED
          : payload.event === 'task.finished'
            ? S2C.AI_FINISHED
            : S2C.AI_EVENT;
        const { event, ...rest } = payload;
        socket.send(encodeFrame(type, rest));
      });
      // Chat frames carry their own type names because a chat has four distinct
      // stream kinds (event/status/turn/closed) rather than the engine's three.
      chats.attach((payload) => {
        if (socket.readyState !== socket.OPEN) return;
        const { event, ...rest } = payload;
        const type = S2C[{
          'chat.event': 'CHAT_EVENT',
          'chat.status': 'CHAT_STATUS',
          'chat.turn': 'CHAT_TURN',
          'chat.closed': 'CHAT_CLOSED',
        }[event]] ?? S2C.CHAT_EVENT;
        socket.send(encodeFrame(type, rest));
      });
      return;
    }

    switch (frame.type) {
      case C2S.PING:
        send(S2C.PONG, { t: frame.t ?? Date.now() });
        break;
      case C2S.STATUS_GET:
        await pushStatus();
        break;
      case C2S.STATUS_SUBSCRIBE:
        stopStatus();
        await pushStatus();
        statusTimer = setInterval(pushStatus, frame.intervalMs > 0 ? frame.intervalMs : STATUS_INTERVAL_MS);
        break;
      case C2S.STATUS_UNSUBSCRIBE:
        stopStatus();
        break;

      case C2S.PROCS_LIST: {
        try {
          const data = await listProcesses();
          const query = typeof frame.query === 'string' ? frame.query : '';
          const items = data.items
            .filter((p) => matchesQuery(p.name, query))
            .slice(0, Number.isInteger(frame.limit) && frame.limit > 0 ? frame.limit : 200);
          send(S2C.PROCS, { capturedAt: data.capturedAt, total: data.items.length, items });
        } catch (err) {
          send(S2C.ERROR, { code: 'procs_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.SERVICES_LIST: {
        try {
          const data = await listServices();
          const query = typeof frame.query === 'string' ? frame.query : '';
          const items = data.items.filter(
            (s) => matchesQuery(s.name, query) || matchesQuery(s.displayName, query),
          );
          send(S2C.SERVICES, { capturedAt: data.capturedAt, total: data.items.length, items });
        } catch (err) {
          send(S2C.ERROR, { code: 'services_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.PROCS_KILL: {
        const result = await killProcess(frame.pid);
        send(S2C.ACTION_RESULT, { action: 'procs.kill', target: frame.pid, ...result });
        if (result.ok) invalidateInventory();
        break;
      }

      // ---- P2: filesystem ----

      case C2S.FS_ROOTS:
        send(S2C.FS_ROOTS, { roots: allowedRoots() });
        break;

      case C2S.FS_LIST:
        try {
          send(S2C.FS_LISTING, await listDirectory(frame.path));
        } catch (err) {
          send(S2C.ERROR, { code: err?.code ?? 'fs_failed', message: String(err?.message ?? err), path: frame.path });
        }
        break;

      case C2S.FS_READ:
        try {
          send(S2C.FS_FILE, await readTextFile(frame.path));
        } catch (err) {
          send(S2C.ERROR, { code: err?.code ?? 'fs_failed', message: String(err?.message ?? err), path: frame.path });
        }
        break;

      case C2S.FS_WRITE:
        try {
          const written = await writeTextFile(frame.path, frame.text);
          send(S2C.FS_WRITTEN, written);
          send(S2C.ACTION_RESULT, {
            action: 'fs.write',
            target: frame.path,
            ok: true,
            code: 'written',
            message: `已保存 ${frame.path}`,
          });
        } catch (err) {
          send(S2C.ACTION_RESULT, {
            action: 'fs.write',
            target: frame.path,
            ok: false,
            code: err?.code ?? 'fs_failed',
            message: `保存失败：${err?.message ?? err}`,
          });
        }
        break;

      case C2S.FS_MKDIR:
        try {
          const made = await createEntry(frame.path, frame.name, frame.kind === 'file' ? 'file' : 'dir');
          send(S2C.ACTION_RESULT, {
            action: 'fs.mkdir',
            target: made.path,
            ok: true,
            code: 'created',
            message: `已创建 ${path.basename(made.path)}`,
          });
          send(S2C.FS_LISTING, await listDirectory(frame.path));
        } catch (err) {
          send(S2C.ACTION_RESULT, {
            action: 'fs.mkdir',
            target: frame.name,
            ok: false,
            code: err?.code ?? 'fs_failed',
            message: `创建失败：${err?.message ?? err}`,
          });
        }
        break;

      case C2S.FS_DELETE:
        try {
          const gone = await deleteEntry(frame.path);
          send(S2C.ACTION_RESULT, {
            action: 'fs.delete',
            target: gone.path,
            ok: true,
            code: 'deleted',
            message: `已删除 ${path.basename(gone.path)}`,
          });
          send(S2C.FS_LISTING, await listDirectory(path.dirname(gone.path)));
        } catch (err) {
          send(S2C.ACTION_RESULT, {
            action: 'fs.delete',
            target: frame.path,
            ok: false,
            code: err?.code ?? 'fs_failed',
            message: `删除失败：${err?.message ?? err}`,
          });
        }
        break;

      case C2S.FS_RENAME:
        try {
          const moved = await renameEntry(frame.path, frame.name);
          send(S2C.ACTION_RESULT, {
            action: 'fs.rename',
            target: moved.to,
            ok: true,
            code: 'renamed',
            message: `已重命名为 ${path.basename(moved.to)}`,
          });
          send(S2C.FS_LISTING, await listDirectory(path.dirname(moved.to)));
        } catch (err) {
          send(S2C.ACTION_RESULT, {
            action: 'fs.rename',
            target: frame.path,
            ok: false,
            code: err?.code ?? 'fs_failed',
            message: `重命名失败：${err?.message ?? err}`,
          });
        }
        break;

      // ---- P3: terminal ----

      case C2S.TERM_OPEN: {
        if (!SHELL_ENABLED) {
          send(S2C.ACTION_RESULT, {
            action: 'term.open',
            target: '',
            ok: false,
            code: 'shell_disabled',
            message: '终端未启用：请在电脑上以 --enable-shell 启动代理',
          });
          break;
        }
        const session = terminals.create();
        terminals.attach((sid, text, stream) => {
          if (socket.readyState === socket.OPEN) {
            socket.send(encodeFrame(S2C.TERM_OUTPUT, { sessionId: sid, text, stream }));
          }
        });
        send(S2C.TERM_OPENED, { sessionId: session.id, scrollback: session.snapshot().scrollback });
        break;
      }

      case C2S.TERM_LIST:
        send(S2C.TERM_LIST, { sessions: terminals.list() });
        break;

      case C2S.TERM_RUN: {
        if (!SHELL_ENABLED) {
          send(S2C.TERM_EXIT, { sessionId: frame.sessionId, code: -1, error: 'shell_disabled' });
          break;
        }
        const target = terminals.get(frame.sessionId);
        if (target === null) {
          send(S2C.TERM_EXIT, { sessionId: frame.sessionId, code: -1, error: 'no_such_session' });
          break;
        }
        const result = await target.run(frame.command);
        send(S2C.TERM_EXIT, {
          sessionId: frame.sessionId,
          code: result.code,
          error: result.error ?? null,
        });
        break;
      }

      case C2S.TERM_INTERRUPT: {
        const target = terminals.get(frame.sessionId);
        const stopped = target ? target.interrupt() : false;
        send(S2C.ACTION_RESULT, {
          action: 'term.interrupt',
          target: frame.sessionId,
          ok: stopped,
          code: stopped ? 'interrupted' : 'nothing_running',
          message: stopped ? '已中断当前命令' : '没有正在运行的命令',
        });
        break;
      }

      case C2S.TERM_CLOSE: {
        const closed = terminals.close(frame.sessionId);
        send(S2C.ACTION_RESULT, {
          action: 'term.close',
          target: frame.sessionId,
          ok: closed,
          code: closed ? 'closed' : 'not_found',
          message: closed ? '已关闭终端' : '终端不存在',
        });
        break;
      }

      // ---- P4: AI engines ----

      case C2S.AI_ENGINES:
        send(S2C.AI_ENGINES, { engines: await engines.probeEngines() });
        break;

      case C2S.AI_TASKS:
        send(S2C.AI_TASKS, { tasks: engines.listTasks() });
        break;

      case C2S.AI_TASK_GET: {
        const task = engines.getTask(frame.taskId);
        if (task === null) {
          send(S2C.ERROR, { code: 'no_such_task', message: `没有任务 ${frame.taskId}` });
        } else {
          send(S2C.AI_TASK, { task });
        }
        break;
      }

      case C2S.AI_SUBMIT: {
        const result = engines.submit({
          engine: frame.engine,
          prompt: frame.prompt,
          cwd: frame.cwd,
          resume: frame.resume !== false,
        });
        if (result.ok) {
          send(S2C.AI_STARTED, { taskId: result.taskId, engine: frame.engine });
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'ai.submit',
            target: frame.engine ?? '',
            ok: false,
            code: result.code,
            message: result.message,
          });
        }
        break;
      }

      case C2S.AI_CANCEL: {
        const cancelled = engines.cancel(frame.taskId);
        send(S2C.ACTION_RESULT, {
          action: 'ai.cancel',
          target: frame.taskId,
          ok: cancelled,
          code: cancelled ? 'cancelled' : 'not_running',
          message: cancelled ? '已取消任务' : '任务不在运行中',
        });
        break;
      }

      case C2S.AI_RESET: {
        const done = engines.resetSession(frame.engine);
        send(S2C.ACTION_RESULT, {
          action: 'ai.reset',
          target: frame.engine ?? '',
          ok: done,
          code: done ? 'reset' : 'bad_engine',
          message: done ? '已清除会话上下文' : '未知引擎',
        });
        break;
      }

      // ---- Codex provider configuration ----

      case C2S.CODEX_GET: {
        try {
          const config = await readCodexConfig();
          const { PROVIDER_TEMPLATES } = await import('./codexconfig.js');
          send(S2C.CODEX_CONFIG, { config, templates: PROVIDER_TEMPLATES, paths: codepaths() });
        } catch (err) {
          send(S2C.ERROR, { code: 'codex_read_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.CODEX_APPLY: {
        const result = await applyProviderConfig({
          providerId: frame.providerId,
          model: frame.model,
          apiKey: frame.apiKey,
          reasoningEffort: frame.reasoningEffort,
          contextWindow: Number.isInteger(frame.contextWindow) ? frame.contextWindow : undefined,
        });
        send(S2C.ACTION_RESULT, {
          action: 'codex.apply',
          target: frame.providerId ?? '',
          ok: result.ok,
          code: result.code,
          message: result.message,
          detail: result.applied ?? null,
        });
        if (result.ok) {
          const config = await readCodexConfig();
          send(S2C.CODEX_CONFIG, { config, paths: codepaths() });
        }
        break;
      }

      case C2S.CODEX_RESTORE: {
        const result = await restoreBackup(frame.name);
        send(S2C.ACTION_RESULT, {
          action: 'codex.restore',
          target: frame.name ?? 'latest',
          ok: result.ok,
          code: result.code,
          message: result.message,
        });
        if (result.ok) {
          const config = await readCodexConfig();
          send(S2C.CODEX_CONFIG, { config, paths: codepaths() });
        }
        break;
      }

      // ---- Existing sessions on disk ----

      case C2S.SESSIONS_LIST: {
        try {
          const all = await listSessions({ engine: frame.engine });
          // Group by working directory so the client can render a workspace
          // index directly, without re-deriving it from raw paths.
          const byWorkspace = new Map();
          for (const s of all) {
            const key = s.cwd ?? '(未知目录)';
            if (!byWorkspace.has(key)) byWorkspace.set(key, []);
            byWorkspace.get(key).push(s);
          }
          const workspaces = [...byWorkspace.entries()].map(([cwd, sessions]) => ({
            cwd,
            count: sessions.length,
            engines: [...new Set(sessions.map((x) => x.engine))],
            latestAt: sessions.reduce(
              (acc, x) => (acc === null || new Date(x.updatedAt) > new Date(acc) ? x.updatedAt : acc),
              null,
            ),
          })).sort((a, b) => new Date(b.latestAt) - new Date(a.latestAt));

          send(S2C.SESSIONS, {
            roots: sessionRoots(),
            total: all.length,
            workspaces,
            sessions: all.slice(0, 600),
          });
        } catch (err) {
          send(S2C.ERROR, { code: 'sessions_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.SESSIONS_READ: {
        try {
          const detail = await readSession({
            engine: frame.engine,
            id: frame.sessionId,
            sessionPath: frame.path,
          });
          if (detail === null) {
            send(S2C.ERROR, { code: 'session_not_found', message: '找不到该会话' });
          } else {
            send(S2C.SESSION, {
              meta: detail.meta,
              events: detail.events,
              totalEvents: detail.totalEvents,
              truncated: detail.truncated,
            });
          }
        } catch (err) {
          send(S2C.ERROR, { code: 'session_read_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      // ---- Live chat over the DSH SDK runtime ----
      //
      // Unlike the one-shot `ai.*` engines, a chat holds a real long-lived
      // session: one DSH SDK runtime process per chat, so the conversation has
      // genuine continuity instead of a re-fed transcript.

      case C2S.CHAT_LIST:
        send(S2C.CHATS, { chats: chats.list() });
        break;

      case C2S.CHAT_CREATE: {
        const created = chats.create({
          cwd: frame.cwd,
          provider: frame.provider,
          model: frame.model,
          title: frame.title,
        });
        send(S2C.CHAT, created);
        send(S2C.CHATS, { chats: chats.list() });
        break;
      }

      case C2S.CHAT_SEND: {
        const result = await chats.send(frame.chatId, frame.text);
        if (result.ok) {
          send(S2C.CHAT_SENT, {
            chatId: frame.chatId,
            seq: result.userSeq?.seq ?? null,
            messageId: result.messageId,
            sessionId: result.sessionId,
          });
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.send',
            target: frame.chatId ?? '',
            ok: false,
            code: result.code,
            message: result.message,
          });
        }
        break;
      }

      case C2S.CHAT_READ: {
        const chat = chats.get(frame.chatId);
        if (!chat) {
          send(S2C.ERROR, { code: 'no_chat', message: '会话不存在' });
        } else {
          send(S2C.CHAT, chat.detail(Number.isInteger(frame.afterSeq) ? frame.afterSeq : 0));
        }
        break;
      }

      case C2S.CHAT_CANCEL: {
        const result = chats.cancel(frame.chatId);
        send(S2C.ACTION_RESULT, {
          action: 'chat.cancel',
          target: frame.chatId ?? '',
          ok: result.ok,
          code: result.ok ? 'cancelled' : result.code,
          message: result.ok ? '已停止该轮回复' : result.message,
        });
        break;
      }

      case C2S.CHAT_CLOSE: {
        const result = chats.close(frame.chatId);
        send(S2C.ACTION_RESULT, {
          action: 'chat.close',
          target: frame.chatId ?? '',
          ok: result.ok,
          code: result.ok ? 'closed' : result.code,
          message: result.ok ? '已关闭会话' : result.message,
        });
        if (result.ok) send(S2C.CHATS, { chats: chats.list() });
        break;
      }

      case C2S.SERVICE_ACTION: {
        const result = await controlService(frame.name, frame.action);
        send(S2C.ACTION_RESULT, {
          action: 'services.action',
          target: frame.name,
          verb: frame.action,
          ...result,
        });
        if (result.ok) invalidateInventory();
        break;
      }

      default:
        send(S2C.ERROR, { code: 'unknown_type', message: `unsupported frame type "${frame.type}"` });
    }
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
      engines.detach();
      chats.detach();
    }
  });

  socket.on('error', (err) => {
    console.error(`[termdesk] socket error from ${peer}: ${err?.message ?? err}`);
  });
});

server.listen(args.port, args.host, () => {
  console.log('TermDesk PC agent listening');
  console.log(`  health : http://127.0.0.1:${args.port}/healthz`);
  for (const addr of localAddresses()) {
    console.log(`  ws     : ws://${addr}:${args.port}`);
  }
  console.log(`  token  : ${token.slice(0, 6)}…  (full token at ${tokenPath()})`);
  console.log(`  shell  : ${SHELL_ENABLED ? 'ENABLED (arbitrary commands allowed)' : 'disabled (start with --enable-shell)'}`);
  console.log(`  roots  : ${allowedRoots().join('  ')}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    terminals.disposeAll();
    engines.disposeAll();
    chats.disposeAll();
    server.close(() => process.exit(0));
    // Do not hang forever waiting for sockets to drain.
    setTimeout(() => process.exit(0), 1500).unref();
  });
}
