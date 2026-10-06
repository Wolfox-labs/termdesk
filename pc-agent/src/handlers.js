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
  readDocxText,
  searchFiles,
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
import { chatEngineIds, getKernel, isAdapterKernel, kernelTier, listKernels } from './kernels/registry.js';
import { threadSummaryToSession, threadToSessionDetail, discoverThreadIdsFromDisk } from './kernels/codex.js';

const STATUS_INTERVAL_MS = 2000;

/**
 * Build the per-connection frame handler.
 *
 * @param {object} ctx
 * @param {(type: string, payload?: object) => void} ctx.send
 * @param {import('ws').WebSocket} ctx.socket
 * @param {boolean} ctx.shellEnabled
 * @param {import('./terminal.js').TerminalManager} ctx.terminals
 * @param {import('./chat.js').ChatManager} ctx.chats
 * @param {() => Promise<void>} ctx.pushStatus
 * @param {() => void} ctx.stopStatus
 * @param {(ms: number) => void} ctx.startStatusTimer
 */
/**
 * The Codex session list: kernel first, rollout filenames for discovery.
 *
 * `thread/list` returns only the kernel's recent window (measured on this
 * machine: 9 of 54 root sessions), while `thread/read` serves ANY thread id.
 * So the ids the index missed come from rollout FILENAMES and every title, cwd
 * and timestamp still comes from the kernel. Every entry in this list is
 * therefore openable and resumable by construction.
 *
 * Cached briefly: a phone refresh must not re-read 80 headers every time.
 */
const CODEX_LIST_TTL_MS = 60_000;
let codexListCache = { at: 0, value: null };

async function listCodexSessions(chats) {
  const now = Date.now();
  if (codexListCache.value && now - codexListCache.at < CODEX_LIST_TTL_MS) return codexListCache.value;

  const server = chats.codexServer();
  const listed = await server.listThreads({ limit: 300 });
  const native = (listed?.data ?? []).map(threadSummaryToSession);
  const known = new Set(native.map((s) => s.id));

  // Archiving is state the rollout files do not carry, so ask for the archived
  // window explicitly and keep those ids out of the disk-discovered extras.
  const archivedIds = new Set(
    ((await server.listThreads({ limit: 500, archived: true }).catch(() => null))?.data ?? []).map((t) => t.id),
  );

  const extraIds = discoverThreadIdsFromDisk({ root: sessionRoots().codex, limit: 80 })
    .filter((id) => !known.has(id) && !archivedIds.has(id));
  const extra = [];
  const BATCH = 8;
  for (let i = 0; i < extraIds.length; i += BATCH) {
    const batch = await Promise.all(
      extraIds.slice(i, i + BATCH).map((id) => server.readThread(id, { includeTurns: false }).catch(() => null)),
    );
    for (const thread of batch) {
      // Child (subagent) rollouts are not root sessions the user can open.
      if (!thread?.id || thread.parentThreadId || thread.threadSource === 'subagent') continue;
      extra.push(threadSummaryToSession(thread));
    }
  }

  // One entry per native thread id: the two discovery paths can overlap when a
  // thread appears both in the kernel window and in a rollout file name.
  const byId = new Map();
  for (const entry of [...native, ...extra]) if (!byId.has(entry.id)) byId.set(entry.id, entry);
  const sessions = [...byId.values()];
  const value = {
    sessions,
    source: `kernel(${native.length})+disk-index(${extra.length})`,
  };
  codexListCache = { at: now, value };
  return value;
}

/**
 * Can this session be continued, and if not, why?
 *
 * Answered on the PC rather than on the phone: this side is what knows whether a
 * kernel has a verified resume entry point. The phone used to hard-code "codex
 * only", so ACP history looked permanently read-only while the kernel could
 * reopen it perfectly well.
 *
 * Module scope, not inside a case arm: both the list and the read path need it,
 * and keeping it in the list arm made every "open this session" fail with
 * "resumeVerdict is not defined".
 */
function resumeVerdict(engineId) {
  const kernel = getKernel(engineId);
  if (!kernel) return { canResume: false, resumeNote: `未知内核 "${engineId}"，无法判断能否继续` };
  if (!kernel.available) return { canResume: false, resumeNote: `${kernel.label} 当前不可用` };
  if (kernel.resume) return { canResume: true, resumeNote: null };
  return { canResume: false, resumeNote: `${kernel.label} 未提供经过验证的恢复入口，这里保持只读` };
}

export function createFrameHandler(ctx) {
  const { send, socket, shellEnabled, terminals, chats } = ctx;

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

      case C2S.FS_SEARCH:
        try {
          send(S2C.FS_RESULTS, await searchFiles(frame.path, frame.query, {
            limit: frame.limit,
            maxDepth: frame.maxDepth,
          }));
        } catch (err) {
          send(S2C.ERROR, { code: err?.code ?? 'search_failed', message: String(err?.message ?? err), path: frame.path });
        }
        break;

      case C2S.FS_DOCTEXT:
        try {
          send(S2C.FS_DOCTEXT, await readDocxText(frame.path));
        } catch (err) {
          send(S2C.ERROR, { code: err?.code ?? 'docx_failed', message: String(err?.message ?? err), path: frame.path });
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

      // ---- Kernel table (replaces the deprecated ai.* task surface) ----
      //
      // One source of truth: the registry that the chat pipeline, the desktop
      // shell and the phone all read. The old `ai.engines` discovery had its own
      // idea of which kernels existed, which is how the picker ended up
      // disagreeing with what a conversation could actually run on.

      case C2S.KERNELS_LIST:
        send(S2C.KERNELS, { kernels: listKernels() });
        break;

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
          // The kernel is the authority for its own sessions; TermDesk only asks.
          // The on-disk scan survives as a fallback so history stays visible when
          // Codex itself is unreachable.
          const wantCodex = !frame.engine || frame.engine === 'codex';
          const wantDsh = !frame.engine || frame.engine === 'dsh';
          const all = [];
          let codexSource = null;
          // ACP kernels keep their own index; asking the kernel is the only way
          // the list can contain exactly the sessions that can be opened again.
          // ACP kernels AND CLI shims keep their own index: both are asked, and
          // asking the kernel is the only way the list can contain exactly the
          // sessions that can be opened again.
          const kernelWanted = frame.engine
            ? (isAdapterKernel(frame.engine) ? [frame.engine] : [])
            : chatEngineIds().filter((id) => isAdapterKernel(id));
          const acpSources = {};
          for (const engineId of kernelWanted) {
            try {
              all.push(...(await chats.listAcpSessions(engineId)));
              acpSources[engineId] = 'kernel';
            } catch (err) {
              acpSources[engineId] = `unavailable: ${String(err?.message ?? err).slice(0, 120)}`;
            }
          }
          if (wantDsh) all.push(...(await listSessions({ engine: 'dsh' })));
          if (wantCodex) {
            try {
              const codexList = await listCodexSessions(chats);
              all.push(...codexList.sessions);
              codexSource = codexList.source;
            } catch (err) {
              // The kernel is the only Codex session source now. Say so instead
              // of quietly falling back to a second, divergent reader.
              codexSource = `kernel-unavailable: ${String(err?.message ?? err).slice(0, 140)}`;
            }
            all.sort((a, b) => new Date(b.updatedAt ?? 0) - new Date(a.updatedAt ?? 0));
          }
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
            sessions: all.slice(0, 600).map((s) => ({ ...s, ...resumeVerdict(s.engine) })),
            // Tells the client where the Codex index came from: the kernel's own
            // session API, or the on-disk fallback when Codex is unreachable.
            codexSource,
            // Per-ACP-kernel index source, so a kernel that is installed but
            // unreachable is reported instead of silently missing.
            acpSources,
          });
        } catch (err) {
          send(S2C.ERROR, { code: 'sessions_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.SESSIONS_READ: {
        try {
          let detail = null;
          if (frame.engine === 'codex') {
            // Native transcript straight from the kernel's own session store.
            try {
              const thread = await chats.codexServer().readThread(frame.sessionId, { includeTurns: true });
              detail = thread ? threadToSessionDetail(thread) : null;
            } catch { detail = null; }
            // No on-disk fallback for Codex any more: the kernel can read any
            // thread id, and a second reader would only drift from it.
          } else if (kernelTier(frame.engine) === 'shim') {
            // A CLI keeps no transcript we can parse, so there is no body to
            // show. Said out loud instead of an empty conversation: the session
            // can still be continued, just not replayed.
            send(S2C.SESSION, {
              meta: {
                engine: frame.engine,
                id: frame.sessionId,
                cwd: frame.cwd ?? null,
                title: null,
                ...resumeVerdict(frame.engine),
                bodyNote: '该内核不提供历史正文，只能继续对话',
              },
              events: [],
              totalEvents: 0,
              truncated: false,
            });
            break;
          } else if (kernelTier(frame.engine) === 'acp') {
            // The kernel replays its own transcript; TermDesk parses nothing.
            detail = await chats.readAcpSession(frame.engine, frame.sessionId);
          } else {
            detail = await readSession({
              engine: frame.engine,
              id: frame.sessionId,
              sessionPath: frame.path,
            });
          }
          if (detail === null) {
            send(S2C.ERROR, { code: 'session_not_found', message: '找不到该会话' });
          } else {
            send(S2C.SESSION, {
              // The same verdict the list carries, so the view that renders the
              // transcript can enable "continue" without a second lookup.
              meta: { ...detail.meta, ...resumeVerdict(frame.engine) },
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
        send(S2C.CHATS, { chats: chats.list(), approvals: chats.approvals.pending() });
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
          send(S2C.CHATS, { chats: chats.list(), approvals: chats.approvals.pending() });
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

      case C2S.CHAT_RESUME: {
        try {
          const result = await chats.resume({ engine: frame.engine, id: frame.sessionId, sessionPath: frame.path });
          if (result.ok) {
            send(S2C.CHAT, result.chat);
            send(S2C.CHATS, { chats: chats.list(), approvals: chats.approvals.pending() });
          } else {
            send(S2C.ACTION_RESULT, { action: 'chat.resume', target: frame.sessionId, ok: false, code: result.code, message: result.message });
          }
        } catch (err) {
          send(S2C.ACTION_RESULT, { action: 'chat.resume', target: frame.sessionId, ok: false, code: 'resume_failed', message: String(err?.message ?? err) });
        }
        break;
      }

      case C2S.CHAT_CONFIG: {
        const result = await chats.setConfig(frame.chatId, {
          model: frame.model,
          effort: frame.effort,
          title: frame.title,
          mode: frame.mode,
        });
        if (result.ok) {
          send(S2C.CHAT, result.chat);
          send(S2C.CHATS, { chats: chats.list(), approvals: chats.approvals.pending() });
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.config',
            target: frame.chatId ?? '',
            ok: false,
            code: result.code,
            message: result.message,
          });
        }
        break;
      }

      case C2S.CHAT_TERMINALS: {
        const result = await chats.chatTerminals(frame.chatId);
        if (result) {
          send(S2C.CHAT_TERMINALS, result);
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.terminals',
            target: frame.chatId ?? '',
            ok: false,
            code: 'no_such_chat',
            message: '没有这个会话',
          });
        }
        break;
      }

      case C2S.CHAT_TERMINAL_READ: {
        const result = chats.chatTerminalRead(frame.chatId, frame.terminalId);
        if (result) {
          send(S2C.CHAT_TERMINAL, result);
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.terminal.read',
            target: frame.terminalId ?? '',
            ok: false,
            code: 'no_such_terminal',
            message: '没有这个终端',
          });
        }
        break;
      }

      case C2S.CHAT_TERMINAL_INPUT: {
        try {
          await chats.chatTerminalWrite(frame.chatId, frame.terminalId, frame.data ?? '');
        } catch (err) {
          // The refusal is explained: "this kernel runs its own commands" and
          // "the terminal is gone" must not look the same on the phone.
          send(S2C.ACTION_RESULT, {
            action: 'chat.terminal.input',
            target: frame.terminalId ?? '',
            ok: false,
            code: 'terminal_refused',
            message: String(err?.message ?? err),
          });
        }
        break;
      }

      case C2S.CHAT_TERMINAL_STOP: {
        try {
          const result = await chats.chatTerminalStop(frame.chatId, frame.terminalId);
          send(S2C.ACTION_RESULT, {
            action: 'chat.terminal.stop',
            target: frame.terminalId ?? '',
            ok: true,
            message: result?.stopped ? '已终止' : (result?.reason ?? '已经结束'),
          });
        } catch (err) {
          send(S2C.ACTION_RESULT, {
            action: 'chat.terminal.stop',
            target: frame.terminalId ?? '',
            ok: false,
            code: 'terminal_refused',
            message: String(err?.message ?? err),
          });
        }
        break;
      }

      case C2S.CHAT_MODELS: {
        const result = await chats.modelsFor(frame.chatId);
        if (result.ok) {
          send(S2C.CHAT_MODELS, result);
        } else {
          send(S2C.ACTION_RESULT, {
            action: 'chat.models',
            target: frame.chatId ?? '',
            ok: false,
            code: result.code,
            message: result.message,
          });
        }
        break;
      }

      case C2S.CHAT_SEND: {
        const result = await chats.send(frame.chatId, frame.text, {
          model: frame.model,
          effort: frame.effort,
        });
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

      case C2S.CHAT_APPROVE: {
        // The phone answering a "may I run this?" question. An unknown or
        // already-settled request is refused, so a stale tap cannot decide
        // something else that happens to be pending.
        const result = chats.resolveApproval({
          requestId: frame.requestId,
          optionId: frame.optionId,
        });
        send(S2C.ACTION_RESULT, {
          action: 'chat.approve',
          target: frame.requestId ?? '',
          ok: result.ok,
          code: result.ok ? 'approved' : result.code,
          message: result.message ?? null,
        });
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
