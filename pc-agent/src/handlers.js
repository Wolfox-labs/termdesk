/**
 * Authenticated frame dispatch for TermDesk.
 *
 * Everything after the auth gate lives here so `server.js` can stay focused on
 * connection lifecycle (accept, authenticate, route, tear down). Each case is
 * a thin adapter: validate the frame, call one domain module, answer with one
 * or two well-typed frames.
 */
import path from 'node:path';

import { C2S, S2C, encodeFrame } from './protocol.js';
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
import {
  applyProviderConfig,
  codepaths,
  readCodexConfig,
  restoreBackup,
  PROVIDER_TEMPLATES,
} from './codexconfig.js';
import { listSessions, readSession, sessionRoots } from './sessions.js';

const STATUS_INTERVAL_MS = 2000;

/**
 * Build the per-connection frame handler.
 *
 * @param {object} ctx
 * @param {(type: string, payload?: object) => void} ctx.send
 * @param {import('ws').WebSocket} ctx.socket
 * @param {boolean} ctx.shellEnabled
 * @param {import('./terminal.js').TerminalManager} ctx.terminals
 * @param {import('./engines.js').EngineManager} ctx.engines
 * @param {import('./chat.js').ChatManager} ctx.chats
 * @param {() => Promise<void>} ctx.pushStatus
 * @param {() => void} ctx.stopStatus
 * @param {(ms: number) => void} ctx.startStatusTimer
 */
export function createFrameHandler(ctx) {
  const { send, socket, shellEnabled, terminals, engines, chats } = ctx;

  return async function handleFrame(frame) {
    switch (frame.type) {
      case C2S.PING:
        send(S2C.PONG, { t: frame.t ?? Date.now() });
        break;
      case C2S.STATUS_GET:
        await ctx.pushStatus();
        break;
      case C2S.STATUS_SUBSCRIBE:
        ctx.stopStatus();
        await ctx.pushStatus();
        ctx.startStatusTimer(frame.intervalMs > 0 ? frame.intervalMs : STATUS_INTERVAL_MS);
        break;
      case C2S.STATUS_UNSUBSCRIBE:
        ctx.stopStatus();
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
        if (!shellEnabled) {
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
        if (!shellEnabled) {
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
      //
      // @deprecated The `ai.*` task pipeline is kept for wire compatibility
      // only. The unified conversation entry is `chat.*` below: create a chat
      // with `engine: 'codex' | 'dsh'` and send turns on it. Do not add new
      // features here.

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

      // ---- Unified chat pipeline (engine = dsh | codex) ----
      //
      // This is the product path for talking to an AI on the machine. Each
      // chat holds a real multi-turn conversation:
      //   engine=dsh   — one resident DSH SDK runtime per chat.
      //   engine=codex — `codex exec` turns continued via `exec resume`
      //                  <thread_id> (the Codex-native thread, not a re-fed
      //                  transcript).
      // The one-shot `ai.*` tasks above are the deprecated legacy surface.

      case C2S.CHAT_LIST:
        send(S2C.CHATS, { chats: chats.list() });
        break;

      case C2S.CHAT_CREATE: {
        const result = chats.create({
          cwd: frame.cwd,
          provider: frame.provider,
          model: frame.model,
          title: frame.title,
          engine: frame.engine,
        });
        if (result.ok) {
          send(S2C.CHAT, result.chat);
          send(S2C.CHATS, { chats: chats.list() });
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.create',
            target: frame.engine ?? '',
            ok: false,
            code: result.code,
            message: result.message,
          });
        }
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
  };
}

/** Push one status snapshot. Exported so the connection can reuse it. */
export async function pushStatusFrame(send) {
  try {
    send(S2C.STATUS, { status: await collectStatus() });
  } catch (err) {
    send(S2C.ERROR, { code: 'status_failed', message: String(err?.message ?? err) });
  }
}
