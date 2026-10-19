/** ChatManager queue-turn methods; mixed onto the prototype by ../chat.js. */
import { liveCall } from '../kernels/dsh-live.js';
import { kernelTier } from '../kernels/registry.js';
import { turnDriver, unknownKernelMessage } from '../kernels/contract.js';
import { MAX_PROMPT_CHARS, MAX_QUEUED_MESSAGES, lookupKernel } from './shared.js';

export const queueMethods = {
  async send(id, text, opts = {}) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    const message = String(text ?? '').trim();
    if (message.length === 0) return { ok: false, code: 'empty_prompt', message: '消息不能为空' };
    if (message.length > MAX_PROMPT_CHARS) {
      return { ok: false, code: 'prompt_too_long', message: `消息过长（上限 ${MAX_PROMPT_CHARS} 字符）` };
    }
    // A slash line the desktop app would execute itself (/compact, /export, /goal,
    // /permission, ...) must not be sent to the model as text. The commands belong
    // to the host that owns the session, so only a live-attached DSH conversation
    // can run them - and they run WITHOUT a model turn.
    if (message.startsWith('/')) {
      const handled = await this.runLiveCommand(chat, message);
      if (handled) return handled;
    }
    // Waiting is the PC's job, not the person's. A kernel takes one prompt at a time, so a
    // message typed while the answer is still coming waits here and starts when the turn
    // ends. It is shown immediately, because a message that disappears until later reads
    // as a message that was lost.
    if (chat.status === 'running') {
      const queue = (chat.queue ??= []);
      if (queue.length >= MAX_QUEUED_MESSAGES) {
        return {
          ok: false,
          code: 'queue_full',
          message: `上一轮还在进行中，排队已满（${MAX_QUEUED_MESSAGES} 条），等它答完再发`,
        };
      }
      const line = chat.push({
        kind: 'message',
        role: 'user',
        text: message,
        optimistic: true,
        queued: true,
      });
      queue.push({ line, text: message, opts });
      chat.lastUsedAt = Date.now();
      this.emitEvent(chat, line);
      return { ok: true, queued: true, position: queue.length, userSeq: line };
    }

    // Per-turn overrides: the kernel documents model and effort as applying to
    // this turn and subsequent turns, so the chat adopts them and stays truthful
    // about what it is actually using.
    if (typeof opts.model === 'string' && opts.model.trim()) chat.model = opts.model.trim();
    if (typeof opts.effort === 'string' && opts.effort.trim()) chat.effort = opts.effort.trim();

    chat.lastUsedAt = Date.now();
    chat.status = 'running';

    // Show the user's own message immediately, before the engine answers. Both
    // engines echo it back (DSH as `user/message`, Codex app-server as a
    // `userMessage` thread item), so the optimistic line is reconciled against
    // that echo instead of being shown twice.
    const userSeq = chat.push({ kind: 'message', role: 'user', text: message, optimistic: true });
    chat.pendingUserEcho = userSeq;
    if (chat.titleSource === 'prompt') {
      const firstLine = message.split(/\r?\n/)[0].slice(0, 60);
      if (firstLine.trim()) {
        chat.title = firstLine.trim();
        chat.titleSource = 'user';
      }
    }

    // One question, three answers. Before this the manager asked by name, so a
    // kernel it had not been taught fell through to the SDK driver and talked a
    // protocol it does not speak. A queued message starts through the same dispatch, so it
    // lives on its own.
    return this.dispatchTurn(chat, message, userSeq);
  }

  /**
   * Hand one prompt to whichever driver this conversation's kernel speaks.
   *
   * Split out of `send` so a queued message can start without re-entering the parts of
   * `send` that decide whether a message may be sent at all — and, more importantly,
   * without pushing a second copy of a line that is already on screen.
   */,

  dispatchTurn(chat, message, userSeq) {
    const driver = turnDriver(chat.engine, lookupKernel);
    if (driver === 'app-server') return this.sendCodex(chat, message, userSeq);
    if (driver === 'acp') return this.sendAcp(chat, message, userSeq);
    if (driver === 'sdk') return this.sendDsh(chat, message, userSeq);
    return this.failUnsupported(chat, userSeq);
  }

  /**
   * Start the next queued message, if there is one and the conversation is free.
   *
   * Called where a turn ends. The line is already in the transcript — it was shown the
   * moment it was typed — so it is NOT pushed again: a second copy is exactly the
   * duplicate this queue must not create, and the phone would draw both. The echo slot is
   * set here instead, because that slot means "the kernel has not echoed this line yet",
   * which only becomes true now.
   *
   * A message that cannot start (a runtime that died while it waited) would stall the
   * queue behind itself forever, because a turn that never began never ends. So the
   * failure is written onto that line and the queue moves on.
   */,

  async drainQueue(chat) {
    if (!chat?.queue || chat.queue.length === 0) return false;
    if (chat.status === 'running') return false;
    const next = chat.queue.shift();
    chat.status = 'running';
    chat.lastUsedAt = Date.now();
    chat.pendingUserEcho = next.line;
    // It is being sent now, so it stops saying "waiting". The phone redraws this line from
    // the event; without it the marker would sit on a message the kernel has already seen.
    if (next.line.queued) {
      next.line.queued = false;
      this.emitEvent(chat, next.line);
    }
    const result = await this.dispatchTurn(chat, next.text, next.line);
    if (result?.ok === false) {
      chat.status = 'idle';
      const record = chat.push({
        kind: 'error',
        role: 'engine',
        text: `排队中的消息没有发出：${result.message ?? result.code ?? '未知原因'}`,
      });
      this.emitEvent(chat, record.seq);
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
      return this.drainQueue(chat);
    }
    return true;
  }

  /**
   * Abandon whatever is still waiting, out loud.
   *
   * Stopping means stopping: a queued message that fires right after a cancel is the
   * opposite of what the person asked for. The lines themselves stay — they were shown
   * when they were typed, and deleting somebody's words is worse than not sending them —
   * and one line says how many will not go.
   */,

  dropQueue(chat, note) {
    const queued = chat.queue?.length ?? 0;
    if (queued === 0) return 0;
    chat.queue = [];
    const record = chat.push({
      kind: 'local',
      role: 'engine',
      text: `${note}：队列中的 ${queued} 条消息未发送`,
    });
    this.emitEvent(chat, record.seq);
    return queued;
  }

  /** One turn on the resident DSH SDK runtime. */,

  cancel(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.status !== 'running') return { ok: false, code: 'not_running', message: '当前没有进行中的回复' };

    // A conversation that belongs to the desktop app is cancelled THERE: this side
    // has no runtime to kill, and killing nothing would leave the app answering.
    if (chat.liveHost) {
      this.stopLiveTail(chat);
      liveCall(chat.liveHost, 'session/cancel', { request: { sessionId: chat.sessionId } }).catch(() => {});
      return this.stopTurn(chat, '已请求桌面端取消本轮回复');
    }

    // Anything the conversation was still asking for is settled as denied, for every
    // driver: the kernel is waiting on those answers, and a turn that is over must not
    // leave a permission prompt on the phone that nothing can resolve.
    this.approvals.cancelForChat(chat.id);

    // A shim kernel is an ACP kernel for driving purposes, and its process must be
    // reachable even before it has named a session. Asked of the table rather than of
    // a name; it is the same condition the old `isCliKernel` check expressed.
    const cancellableWithoutSession = kernelTier(chat.engine) === 'shim';

    switch (this.capabilitiesOf(chat.engine).cancel) {
      case 'ask-kernel': {
        // The kernel cancels its own turn and the thread stays resumable, so there is
        // nothing to kill locally.
        if (chat.threadId) this.codex?.interruptTurn(chat.threadId).catch(() => {});
        return this.stopTurn(chat);
      }
      case 'close-session': {
        // ACP cancel is a notification, so there is nothing to await: the turn is
        // closed out here and the prompt response that follows is ignored
        // (finishAcpTurn returns early once status is 'stopped').
        const kernel = this.acp.get(chat.engine);
        if (kernel && (chat.sessionId || cancellableWithoutSession)) kernel.cancel(chat.sessionId);
        return this.stopTurn(chat);
      }
      case 'kill-process': {
        this.dispose(chat, 'cancelled');
        return this.stopTurn(chat, '已停止（该内核协议无单轮取消，运行时已终止）');
      }
      default:
        // Unreachable for a selectable kernel (the registry only offers kernels that
        // have a driver), and said out loud rather than falling through to "stop the
        // runtime" - which would be a guess about a kernel this build does not know.
        return {
          ok: false,
          code: 'unsupported_kernel',
          message: unknownKernelMessage(chat.engine, lookupKernel)
            ?? `${chat.engine} 内核没有可用的停止方式`,
        };
    }
  }

  /** The part every stop shares: the note, the two events, and the answer. */,

  stopTurn(chat, note) {
    chat.status = 'stopped';
    chat.push({ kind: 'error', role: 'engine', text: note ?? '已停止本轮回复' });
    // Stopping stops the whole thing, queue included: a message that fires seconds after a
    // cancel is the opposite of what was asked for.
    this.dropQueue(chat, '已停止');
    this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'stopped' });
    return { ok: true };
  }

  /** The contract, for one engine, in the shape this file asks its questions in. */,
};
