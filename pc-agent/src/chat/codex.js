/** ChatManager codex-turn methods; mixed onto the prototype by ../chat.js. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import { Chat } from './chat.js';
import { MAX_CHATS, PROMPT_TIMEOUT_MS, lookupKernel } from './shared.js';
import { notificationToChatEvents, turnToChatEvents } from '../kernels/codex.js';
import { turnDriver } from '../kernels/contract.js';

export const codexMethods = {
  async sendCodex(chat, message, userSeq) {
    const server = this.codexServer();
    try {
      if (!chat.threadId) {
        const started = await server.startThread({
          cwd: chat.cwd,
          ...(chat.model ? { model: chat.model } : {}),
          ...(chat.provider ? { modelProvider: chat.provider } : {}),
        });
        chat.threadId = started?.thread?.id ?? null;
        chat.sessionId = chat.threadId ?? chat.sessionId;
        if (!chat.threadId) throw new Error('thread/start 未返回会话标识');
      } else {
        // A thread this app-server already holds cannot be resumed a second
        // time; that is not an error, the next turn simply continues it.
        await server.resumeThread(chat.threadId, { cwd: chat.cwd }).catch((err) => {
          if (!/active writer/i.test(String(err?.message ?? err))) throw err;
        });
      }
      this.codexThreads.set(chat.threadId, chat);

      chat.ready = true;
      chat.status = 'running';
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });

      this.armCodexWatchdog(chat);
      const turn = await server.startTurn(chat.threadId, message, {
        ...(chat.model ? { model: chat.model } : {}),
        ...(chat.effort ? { effort: chat.effort } : {}),
      });
      chat.currentTurnId = turn?.turn?.id ?? null;
      return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
    } catch (err) {
      this.clearCodexWatchdog(chat);
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `Codex 调用失败：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      return { ok: false, code: 'prompt_failed', message: chat.lastError, userSeq };
    }
  }

  // --- Codex app-server plumbing -------------------------------------------

  /** Lazily start the shared app-server and wire notification routing. */,

  codexServer() {
    if (!this.codex) {
      const resolved = this.codexSpawnSpec('codex');
      if (!resolved?.bin) throw new Error('Codex app-server 启动入口不可用');
      const server = this.codexServerFactory({
        bin: resolved.bin,
        args: [...(resolved.args ?? []), 'app-server', '--listen', 'stdio://'],
        log: (line) => console.log(line),
      });
      server.on('notification', (method, params) => this.handleCodexNotification(method, params));
      // Codex's own approval requests become questions on the phone.
      server.approvalHandler = (method, params) => this.answerCodexApproval(method, params);
      server.on('serverRequest', (method) => {
        console.error(`[codex] 引擎请求未实现，已拒绝：${method}`);
      });
      server.on('exit', (code) => {
        for (const chat of this.chats.values()) {
          if (chat.engine !== 'codex' || chat.status !== 'running') continue;
          this.clearCodexWatchdog(chat);
          chat.status = 'failed';
          chat.lastError = `codex app-server 退出（${code}）`;
          chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
          this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
          this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
        }
        this.codex = null;
      });
      this.codex = server;
    }
    return this.codex;
  }

  // --- the conversation's command lines ------------------------------------

  /**
   * What this conversation has been running.
   *
   * Asking for the list is what tells the agent somebody is watching, so live
   * output starts flowing; without it a build log would be pushed at a phone
   * that is looking at another screen.
   */,

  async resumeCodex(id) {
    const server = this.codexServer();
    let thread = null;
    try {
      thread = (await server.resumeThread(id, {}))?.thread ?? null;
    } catch (err) {
      const message = String(err?.message ?? err);
      // The kernel refuses a thread that another process is actively writing
      // (the Codex desktop app holding a conversation open). Say so instead of
      // pretending the history is unavailable.
      if (/active writer/i.test(message)) {
        const mine = await server.listLoadedThreads().catch(() => []);
        if (!mine.includes(id)) {
          return { ok: false, code: 'session_busy', message: '该会话正在电脑端被使用，手机上暂时无法接手' };
        }
      }
      thread = await server.readThread(id, { includeTurns: true }).catch(() => null);
      if (!thread) return { ok: false, code: 'no_session', message: `内核无法恢复该会话：${message}` };
    }
    if (!thread?.id) return { ok: false, code: 'no_session', message: '找不到匹配的原生会话' };
    if (!thread.turns) thread = (await server.readThread(id, { includeTurns: true })) ?? thread;

    const cwd = thread.cwd;
    if (!cwd || !fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory()) {
      return { ok: false, code: 'missing_workspace', message: '原会话的工作目录不存在，不能静默切换到其他目录' };
    }
    const adopted = [...this.chats.values()].find(
      (c) => c.sessionId === id && turnDriver(c.engine, lookupKernel) === 'app-server',
    );
    if (adopted) return { ok: true, chat: adopted.detail() };

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: thread.name || (thread.preview ?? '').split('\n')[0].slice(0, 60) || '历史对话',
      cwd,
      engine: 'codex',
      provider: thread.modelProvider ?? null,
      model: thread.model ?? null,
      effort: thread.reasoningEffort ?? null,
      threadId: id,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    const created = typeof thread.createdAt === 'number' ? thread.createdAt : Date.now();
    chat.createdAt = created < 1e12 ? created * 1000 : created;
    for (const turn of thread.turns ?? []) {
      for (const event of turnToChatEvents(turn)) chat.push(event);
    }
    chat.push({ kind: 'local', role: 'engine', text: '已恢复原生会话 · 后续消息延续原上下文' });
    this.chats.set(chat.id, chat);
    this.codexThreads.set(id, chat);
    return { ok: true, chat: chat.detail() };
  },

  // --- approvals -----------------------------------------------------------

  findChatByThread(threadId) {
    if (!threadId) return null;
    return this.codexThreads.get(threadId) ?? null;
  }

  /**
   * Fallback for an app-server request that does not name its thread.
   *
   * Kept as a method (the approval broker is wired to it) but no longer written in
   * terms of one kernel's name: it is "the newest conversation on a driver that
   * routes by thread".
   */,

  newestCodexChat() {
    const list = [...this.chats.values()]
      .filter((c) => turnDriver(c.engine, lookupKernel) === 'app-server')
      .sort((a, b) => b.lastUsedAt - a.lastUsedAt);
    return list[0] ?? null;
  }

  /** Route one app-server notification into the right chat transcript. */,

  handleCodexNotification(method, params) {
    const threadId = params?.threadId ?? null;
    const chat = threadId ? this.codexThreads.get(threadId) : null;

    if (method === 'turn/started') { if (chat) chat.currentTurnId = params?.turn?.id ?? null; return; }
    if (method === 'turn/completed') { if (chat) this.finishCodexTurn(chat, params?.turn ?? {}); return; }
    if (!chat || chat.status === 'stopped') return;

    // The command lines this conversation runs. Codex executes them inside its
    // own process, so the honest offer is: listed, watchable while it runs,
    // stoppable — but not typeable. `process/writeStdin` and `command/exec/write`
    // both require a process this client created, and this one is not.
    if (method === 'item/started' && params?.item?.type === 'commandExecution') {
      const item = params.item;
      if (item.id) {
        chat.startTerminal({
          id: item.id,
          origin: 'agent',
          command: item.command ?? '',
          cwd: item.cwd ?? chat.cwd ?? null,
        });
        this.emitTerminalList(chat);
      }
    }
    if (method === 'item/commandExecution/outputDelta') {
      const id = params?.itemId;
      if (id && chat.terminals.has(id)) {
        const delta = typeof params?.delta === 'string' ? params.delta : '';
        chat.appendTerminal(id, delta);
        if (delta && this.terminalWatch.has(chat.id)) {
          this.emit({ event: 'chat.terminal.output', chatId: chat.id, terminalId: id, chunk: delta });
        }
      }
    }
    if (method === 'item/completed' && params?.item?.type === 'commandExecution') {
      const item = params.item;
      const record = item.id ? chat.terminals.get(item.id) : null;
      if (record) {
        // The completed item carries the whole output; streamed deltas are the
        // same bytes, so the aggregate is only used when nothing streamed.
        const aggregated = String(item.aggregatedOutput ?? item.aggregated_output ?? '');
        if (aggregated && record.bytes === 0) chat.appendTerminal(item.id, aggregated);
        chat.finishTerminal(item.id, { exitCode: item.exitCode ?? item.exit_code ?? null });
        this.emitTerminalList(chat);
      }
    }
    if (method === 'item/commandExecution/terminalInteraction' && params?.itemId) {
      // The agent answered a prompt in its own terminal. Recording it is how the
      // phone can tell "waiting for input" apart from "just quiet".
      const record = chat.terminals.get(params.itemId);
      if (record) record.lastInputAt = Date.now();
    }

    if (method === 'thread/tokenUsage/updated' && params?.usage) chat.usage = params.usage;
    for (const event of notificationToChatEvents(method, params)) {
      if (event.kind === 'turn') continue; // lifecycle handled above
      if (event.kind === 'message' && event.role === 'user') {
        // The kernel echoes the user's own message back as a thread item. The
        // phone already shows that line (optimistic), so confirm it and drop
        // the echo — otherwise the user's words appear twice.
        const pending = chat.pendingUserEcho;
        if (pending && pending.text.trim() === event.text.trim()) {
          chat.pendingUserEcho = null;
          pending.optimistic = false;
          this.emitEvent(chat, pending);
        }
        continue;
      }
      if (event.streaming) {
        // Deltas are a PREVIEW of the message the kernel sends when the block
        // finishes (item/completed), so each one replaces the last and the whole
        // set is dropped once the authoritative item arrives. ACP works the other
        // way round — see handleAcpUpdate — and using this rule there deleted the
        // entire answer.
        this.discardPreviews(chat, event.kind);
        const record = chat.push(event);
        chat.previews.push(record);
        this.emitEvent(chat, record, { stream: true });
        continue;
      }
      if (event.kind === 'message' || event.kind === 'reasoning') this.discardPreviews(chat, event.kind);
      this.pushAndEmit(chat, event);
    }
  }

  /** Close out a finished turn exactly once. */,

  finishCodexTurn(chat, turn) {
    if (chat.status === 'stopped') return;
    this.clearCodexWatchdog(chat);
    chat.currentTurnId = null;
    const failed = turn?.status === 'failed';
    const cancelled = turn?.status === 'interrupted';
    this.discardPreviews(chat, 'message');
    this.discardPreviews(chat, 'reasoning');
    if (failed) {
      chat.status = 'failed';
      chat.lastError = turn?.error?.message ?? '回合失败';
      chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
    } else {
      chat.status = 'idle';
      chat.lastError = null;
    }
    // 产物兜底：内核自己跑脚本写出来的文件不走写文件工具，这里补一张产出物卡片。
    this.settleProducedFiles(chat);
    chat.push({
      kind: 'turn', role: 'engine', text: '',
      meta: { state: failed ? 'failed' : 'ended', reason: cancelled ? 'interrupted' : null },
    });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: failed ? 'failed' : 'ended' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
    this.notify(this.turnNotification(chat, failed ? 'failed' : 'ended'));
    // The turn is over, so anything typed during it can start now. A failed turn is not
    // drained: the next message would go into the same wall.
    if (!failed) this.drainQueue(chat).catch(() => {});
  },

  armCodexWatchdog(chat) {
    this.clearCodexWatchdog(chat);
    chat.codexTimer = setTimeout(() => {
      if (chat.status !== 'running') return;
      chat.status = 'failed';
      chat.lastError = 'turn timeout';
      chat.push({ kind: 'error', role: 'engine', text: '回复超时（30 分钟）' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    }, PROMPT_TIMEOUT_MS);
    chat.codexTimer.unref?.();
  },

  clearCodexWatchdog(chat) {
    if (chat.codexTimer) { clearTimeout(chat.codexTimer); chat.codexTimer = null; }
  }

  // --- ACP plumbing (engine tier 'acp') ------------------------------------

  /**
   * The shared ACP process for a kernel, started on first use.
   *
   * Notifications are routed by session id, and permissions are answered inside
   * the adapter (an unanswered permission request blocks the turn forever). A
   * permission decision is written into the transcript so the user can always
   * see what the kernel was allowed to do on their machine.
   */,
};
