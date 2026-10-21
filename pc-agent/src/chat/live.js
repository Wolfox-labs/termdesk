/** ChatManager live-turn methods; mixed onto the prototype by ../chat.js. */
import { PROMPT_TIMEOUT_MS } from './shared.js';
import { followSession, liveExecuteCommand, liveListCommands, livePrompt } from '../kernels/dsh-live.js';
import { dshEventToChatEvent, lastWriteOf } from '../sessions.js';

export const liveMethods = {
  async sendDsh(chat, message, userSeq) {
    // A conversation opened from history while the desktop app is running belongs
    // to that app's live agent. Sending here (a second runtime) would be refused
    // by the single-writer rule, so the message goes through the app's own API
    // instead and the answer is read back from the journal it appends to.
    if (chat.liveHost) return this.sendDshLive(chat, message, userSeq);
    try {
      await this.ensureRuntime(chat);
    } catch (err) {
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `无法启动 DSH 运行时：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'spawn_failed', message: chat.lastError, userSeq };
    }

    chat.status = 'running';
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    this.emitEvent(chat, userSeq);

    let receipt;
    try {
      receipt = await chat.request(
        'session/prompt',
        { sessionId: chat.sessionId, contentBlocks: [{ type: 'text', text: message }] },
        PROMPT_TIMEOUT_MS,
      );
    } catch (err) {
      chat.status = 'failed';
      chat.lastError = String(err?.message ?? err);
      chat.push({ kind: 'error', role: 'engine', text: `发送失败：${chat.lastError}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'prompt_failed', message: chat.lastError, userSeq };
    }

    chat.runtimeStarted = true;
    return { ok: true, userSeq, messageId: receipt?.messageId ?? null, sessionId: chat.sessionId };
  }

  /**
   * Run one slash command in the live session, or return null when the line is
   * ordinary text that merely starts with `/`.
   *
   * The phone's own `/` panel is a fixed short list, so the commands the desktop
   * app exposes (compact, export, goal, permission, feedback) were unreachable
   * there. They are executed by the host and do not consume a model turn, so this
   * is the same operation the desktop composer performs - not a fabricated one.
   */,

  async runLiveCommand(chat, line) {
    if (chat.engine !== 'dsh') return null;
    const match = /^\/([^\s]+)\s*([\s\S]*)$/.exec(line);
    if (!match) return null;
    const name = match[1];
    const rest = match[2] ?? '';

    if (name === '命令' || name === 'help' || name === 'commands') {
      const catalog = chat.liveHost
        ? await liveListCommands(chat.liveHost, chat.sessionId)
        : { ok: false, commands: [], message: '桌面版 DeepSeek Harness 没有在运行，命令列表不可用' };
      const body = catalog.ok && catalog.commands.length > 0
        ? catalog.commands.map((c) => `/${c.name}${c.hint ? ' ' + c.hint : ''} — ${c.description}`).join('\n')
        : catalog.message ?? '没有可用命令';
      const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
      this.emitEvent(chat, userLine);
      this.pushAndEmit(chat, { kind: 'local', role: 'engine', text: body });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }

    // Without the live host there is no command registry to ask, so the honest
    // move is to say which commands need it rather than to guess at a catalog.
    if (!chat.liveHost) {
      if (['compact', 'export', 'goal', 'permission', 'feedback', 'plan'].includes(name)) {
        const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
        this.emitEvent(chat, userLine);
        this.pushAndEmit(chat, {
          kind: 'error',
          role: 'engine',
          text: `/${name} 由桌面版 DeepSeek Harness 执行；现在桌面版没有运行，这个命令不可用`,
        });
        return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
      }
      return null;
    }

    // Ask the host which commands exist, then only intercept names it really has:
    // an unknown `/word` is ordinary text and must reach the model unchanged.
    if (!Array.isArray(chat.liveCommands) || Date.now() - (chat.liveCommandsAt ?? 0) > 60_000) {
      const catalog = await liveListCommands(chat.liveHost, chat.sessionId);
      // A failed read is not an empty catalog. Storing [] made every name look
      // unknown, and the caller then sent the line as a prompt — how a `/compact`
      // became a paid model turn on a busy desktop. Null means "unknown", and the
      // branch below refuses instead of guessing.
      chat.liveCommands = catalog.ok ? catalog.commands : null;
      chat.liveCommandsAt = catalog.ok ? Date.now() : 0;
    }
    if (chat.liveCommands === null) {
      const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
      this.emitEvent(chat, userLine);
      this.pushAndEmit(chat, {
        kind: 'error',
        role: 'engine',
        text: `桌面端正在忙，暂时读不到命令列表；这条 /${name} 没有执行。稍后重试，或去掉开头的 / 作为普通消息发送`,
      });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }
    if (!chat.liveCommands.some((c) => c.name === name)) return null;

    const userLine = chat.push({ kind: 'message', role: 'user', text: line, command: true });
    this.emitEvent(chat, userLine);
    const result = await liveExecuteCommand(chat.liveHost, chat.sessionId, line);
    if (result.unknown) {
      this.pushAndEmit(chat, { kind: 'error', role: 'engine', text: `/${name} 没有执行：桌面端不认识这条命令` });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }
    if (!result.ok) {
      this.pushAndEmit(chat, { kind: 'error', role: 'engine', text: `/${name} 失败：${result.message}` });
      return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
    }
    this.pushAndEmit(chat, {
      kind: result.kind === 'error' ? 'error' : 'local',
      role: 'engine',
      text: `/${name}：${result.text || (result.kind === 'error' ? '没有执行' : '已完成')}`,
    });
    return { ok: true, userSeq: userLine, messageId: null, sessionId: chat.sessionId };
  }


  /**
   * One turn through the desktop app's live agent.
   *
   * TermDesk does not own the runtime here and must not touch the journal: it
   * queues the message over the app's own `/api/session/prompt`, then follows the
   * events the app appends. The user's line is shown immediately and reconciled
   * with the runtime's echo, exactly like the resident-runtime path.
   */,

  async sendDshLive(chat, message, userSeq) {
    const write = lastWriteOf({ engine: 'dsh', id: chat.sessionId });
    const file = write?.path ?? null;
    if (!file) {
      const messageText = '找不到这条会话的日志，无法跟随桌面端的回复';
      chat.status = 'failed';
      chat.lastError = messageText;
      chat.push({ kind: 'error', role: 'engine', text: messageText });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      return { ok: false, code: 'no_journal', message: messageText, userSeq };
    }

    // The stream is watched for the whole attachment, not just this turn: the
    // desktop user may type there too, and both ends must show one conversation.
    this.startLiveWatch(chat, chat.sessionId);

    chat.status = 'running';
    chat.ready = true;
    chat.livePendingText = message;
    chat.livePendingLine = userSeq ?? chat.pendingUserEcho ?? null;
    chat.liveQueued = false;
    chat.liveQueuedNoted = false;
    chat.liveEchoSeen = false;
    chat.liveTurnStarted = false;
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    if (chat.livePendingLine) this.emitEvent(chat, chat.livePendingLine);

    const result = await livePrompt(chat.liveHost, {
      sessionId: chat.sessionId,
      text: message,
      clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });

    if (!result.ok) {
      this.clearLivePending(chat);
      chat.status = 'failed';
      chat.lastError = result.message;
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      const hint = result.code === 'transport'
        ? '（桌面端的窗口可能已关闭：重新打开后再发，或改用本机运行时）'
        : '';
      chat.push({ kind: 'error', role: 'engine', text: `桌面端没有接受这条消息：${result.message}${hint}` });
      return { ok: false, code: 'live_prompt_failed', message: result.message, userSeq };
    }
    this.armLiveDeadline(chat);
    return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
  }

  /**
   * Follow the desktop app's journal for as long as this chat is attached.
   *
   * One watcher for the whole conversation (not one per turn): the desktop user can
   * type there at any time, and the phone is meant to show the same conversation.
   * It starts at the current end so history is not replayed twice.
   */
  /**
   * Follow the live conversation through the desktop app's own event stream.
   *
   * The stream carries the durable events AND the model's live chunk frames, which
   * is the only way to show an answer while it is being written: the v4 journal
   * keeps finished messages only. It reconnects on its own, and a fresh snapshot
   * after a reconnect fills the gap; durable events are deduped by seq.
   */,

  startLiveWatch(chat, sessionId) {
    if (chat.liveStream) return;
    chat.liveClosed = false;
    chat.liveSeenSnapshot = false;
    if (typeof chat.liveLastSeq !== 'number') chat.liveLastSeq = -1;
    const open = () => {
      if (chat.liveClosed) return;
      chat.liveStream = followSession(chat.liveHost, {
        sessionId,
        onSnapshot: (snapshot) => this.handleLiveSnapshot(chat, snapshot),
        onEvent: (event) => this.handleLiveEvent(chat, event),
        onChunk: (frame) => this.handleLiveChunk(chat, frame),
        onError: () => {},
        onClose: () => {
          chat.liveStream = null;
          if (chat.liveClosed) return;
          chat.liveReconnect = setTimeout(open, 1200);
          chat.liveReconnect.unref?.();
        },
      });
    };
    open();
  },

  stopLiveTail(chat) {
    chat.liveClosed = true;
    if (chat.liveReconnect) {
      clearTimeout(chat.liveReconnect);
      chat.liveReconnect = null;
    }
    if (chat.liveDeadline) {
      clearTimeout(chat.liveDeadline);
      chat.liveDeadline = null;
    }
    if (chat.liveStream) {
      try { chat.liveStream.close(); } catch { /* already closed */ }
      chat.liveStream = null;
    }
  }

  /**
   * The opening snapshot.
   *
   * On the first open it is history this side already seeded from the journal, so
   * it only sets the seq floor (the stream continues from cursor+1). After a
   * reconnect the same page is the gap filler: records above the floor are handed
   * to the normal event path.
   */,

  handleLiveSnapshot(chat, snapshot) {
    const records = Array.isArray(snapshot?.records) ? snapshot.records : [];
    const cursor = typeof snapshot?.cursor === 'number' ? snapshot.cursor : null;
    if (!chat.liveSeenSnapshot) {
      chat.liveSeenSnapshot = true;
      if (cursor !== null) chat.liveLastSeq = Math.max(chat.liveLastSeq, cursor);
      return;
    }
    for (const record of records) {
      const event = record?.event;
      if (!event || typeof event.seq !== 'number' || event.seq <= chat.liveLastSeq) continue;
      chat.liveLastSeq = event.seq;
      this.handleLiveJournalEvent(chat, event);
    }
    if (cursor !== null) chat.liveLastSeq = Math.max(chat.liveLastSeq, cursor);
  },

  handleLiveEvent(chat, event) {
    if (!event) return;
    if (typeof event.seq === 'number') {
      if (event.seq <= chat.liveLastSeq) return;
      chat.liveLastSeq = event.seq;
    }
    this.handleLiveJournalEvent(chat, event);
  }

  /**
   * One live chunk frame from the app's `agent/assistant-stream`.
   *
   * `frame.chunk` is the same shape the resident runtime sends as `assistant/chunk`,
   * so the preview rules in chat/stream.js are reused rather than copied: deltas
   * grow one record that is re-emitted, and the finished `assistant/message` later
   * replaces it.
   */,

  handleLiveChunk(chat, frame) {
    if (!frame || frame.type !== 'chunk' || !frame.chunk) return;
    // A single long generation can outlive the idle window on its own.
    chat.lastUsedAt = Date.now();
    this.handleSessionEvent(chat, {
      event: { type: 'assistant/chunk', data: { chunk: frame.chunk } },
    });
  }

  /** The state a pending live message owns; cleared on every exit path. */,

  clearLivePending(chat) {
    chat.livePendingText = null;
    chat.livePendingLine = null;
    chat.liveQueued = false;
    chat.liveQueuedNoted = false;
    chat.liveEchoSeen = false;
    chat.liveTurnStarted = false;
    chat.pendingUserEcho = null;
    if (chat.liveDeadline) {
      clearTimeout(chat.liveDeadline);
      chat.liveDeadline = null;
    }
  },

  armLiveDeadline(chat) {
    if (chat.liveDeadline) clearTimeout(chat.liveDeadline);
    chat.liveDeadline = setTimeout(() => {
      if (chat.status !== 'running') return;
      chat.status = 'failed';
      chat.lastError = '等待桌面端回复超时';
      this.clearLivePending(chat);
      chat.push({ kind: 'error', role: 'engine', text: '等待桌面端回复超时（45 分钟）：可以重新发送，或到电脑上继续' });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    }, 45 * 60 * 1000);
    chat.liveDeadline.unref?.();
  }

  /** The text of one inbox item the runtime recorded. */,

  handleLiveJournalEvent(chat, event) {
    const type = event?.type;

    // Following a live conversation is activity, even though the phone sends
    // nothing. Without this the idle reaper freed the chat 45 minutes after the
    // phone's last message — mid-conversation, because the desktop's own turns were
    // not counted — and the phone was left with a frozen transcript.
    chat.lastUsedAt = Date.now();

    // Queue bookkeeping: this is how the desktop says "your message is waiting"
    // and later "it was deleted", and without it the phone kept showing a
    // cancelled message as still queued (and stayed "running" for ever).
    if (type === 'agent/inbox/spliced') {
      this.handleLiveInbox(chat, event);
      return;
    }

    if (type === 'user/message') {
      const normalized = dshEventToChatEvent(event);
      if (normalized?.role === 'user' && this.isLiveEcho(chat, normalized.text)) {
        chat.liveEchoSeen = true;
        if (chat.liveQueued) {
          chat.liveQueued = false;
          chat.liveQueuedNoted = false;
          this.pushAndEmit(chat, { kind: 'local', role: 'engine', text: '桌面端开始处理这条消息' });
        }
      }
    }
    // A turn that starts only after OUR echoed message is the turn we queued.
    // Until then, `turn/end` belongs to a turn that was already running there.
    if (type === 'turn/start' && chat.liveEchoSeen) chat.liveTurnStarted = true;

    this.handleSessionEvent(chat, { event });

    if (type === 'turn/end' && chat.liveEchoSeen && chat.liveTurnStarted) {
      this.finishLiveTurn(chat);
    }
  },

  isLiveEcho(chat, text) {
    const pending = (chat.livePendingText ?? '').trim();
    return pending.length > 0 && String(text ?? '').trim() === pending;
  }

  /**
   * Reconcile TermDesk's view with the desktop's inbox.
   *
   * The desktop records `agent/inbox/spliced`: an insertion when the message waits
   * behind a running turn, and a removal with `outcome: "canceled"` when the
   * person deletes it from the queue there. Both must reach the phone, otherwise a
   * deleted message still looks queued and the chat never leaves "running".
   */,

  handleLiveInbox(chat, event) {
    const data = event?.data ?? {};
    if (data.target !== 'next-turn') return;
    const textOf = (item) => (Array.isArray(item?.content) ? item.content : [])
      .map((block) => (typeof block?.text === 'string' ? block.text : ''))
      .join('')
      .trim();
    const pending = (chat.livePendingText ?? '').trim();
    const inserted = Array.isArray(data.inserted) ? data.inserted : [];
    const ours = pending.length > 0 && inserted.some((item) => textOf(item) === pending);

    if (ours && !chat.liveEchoSeen) {
      chat.liveQueued = true;
      if (!chat.liveQueuedNoted) {
        chat.liveQueuedNoted = true;
        this.pushAndEmit(chat, {
          kind: 'local',
          role: 'engine',
          text: '已交给桌面端：这条消息排在电脑端当前回合之后，还没开始处理',
        });
      }
      return;
    }

    if (data.outcome === 'canceled' && chat.liveQueued && !chat.liveEchoSeen) {
      const line = chat.livePendingLine;
      this.clearLivePending(chat);
      chat.status = 'idle';
      if (line) {
        line.canceled = true;
        this.emitEvent(chat, line);
      }
      this.pushAndEmit(chat, {
        kind: 'error',
        role: 'engine',
        text: '这条消息没有执行：电脑端把排队中的它删除了。手机端现在和电脑一致——它不会再发出去',
      });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'cancelled' });
      this.settleProducedFiles(chat);
      this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
      this.drainQueue(chat).catch(() => {});
    }
  }

  /** The live turn is over: only the status changes, the watcher keeps mirroring. */,

  finishLiveTurn(chat) {
    if (chat.status !== 'running') return;
    this.clearLivePending(chat);
    chat.status = 'idle';
    // 产物兜底：内核自己写出来的文件不走写文件工具，回合结束时扫一遍工作目录。
    this.settleProducedFiles(chat);
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
    this.notify(this.turnNotification(chat, 'ended'));
    this.drainQueue(chat).catch(() => {});
  }

  /**
   * One Codex turn, driven through the kernel's own app-server.
   *
   * The thread IS the session: a fresh chat starts one (`thread/start`), an
   * opened history entry is attached (`thread/resume`). Both then send through
   * `turn/start` and receive the answer as notifications. No transcript is ever
   * re-fed, and TermDesk keeps no per-chat Codex process.
   */,
};
