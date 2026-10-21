/** ChatManager runtime-turn methods; mixed onto the prototype by ../chat.js. */
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { ensureOverlay, routeConfig, routeConfigError } from '../kernels/dsh.js';
import { launchArgs, launchEnv } from '../kernels/dsh-runtime.js';
import { dshEventToChatEvent } from '../sessions.js';
import { discardPreviews as discardStreamedPreviews, handleAssistantChunk as handleStreamChunk } from './stream.js';
import { INIT_TIMEOUT_MS, dshRuntime } from './shared.js';

export const runtimeMethods = {
  async ensureRuntime(chat) {
    if (chat.child && chat.child.exitCode === null && chat.ready) return;

    const runtime = dshRuntime();
    if (!runtime) {
      throw new Error(
        '找不到 DSH 入口：本机没有可用的 dsh 启动器，也没有检测到已安装的 DeepSeek Harness 桌面版',
      );
    }

    // The sdk profile's inherited provider configuration is not known to this
    // runtime, so require the DSH route to be explicit before writing an overlay.
    let patch = null;
    try {
      const route = routeConfig(process.env, { provider: chat.provider, model: chat.model });
      const routeError = routeConfigError(route);
      if (routeError) throw new Error(routeError);
      patch = ensureOverlay(route);
    } catch (err) {
      throw new Error(`DSH 配置无效：${err.message}`);
    }
    const child = spawn(runtime.command, launchArgs(runtime, { patch }), {
      cwd: chat.cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      // The Electron host needs ELECTRON_RUN_AS_NODE; a plain Node entry adds
      // nothing. Inherit everything else (PATH, credentials, DSH_HOME).
      env: launchEnv(runtime, process.env),
    });
    chat.child = child;
    chat.ready = false;

    child.on('error', (err) => {
      chat.lastError = err.message;
      chat.status = 'failed';
      this.failPending(chat, new Error(err.message));
      chat.push({ kind: 'error', role: 'engine', text: `运行时启动失败：${err.message}` });
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
    });

    child.on('close', (code) => {
      chat.ready = false;
      this.failPending(chat, new Error(`DSH 运行时已退出（退出码 ${code}）`));
      if (chat.status === 'shutting-down') return;
      if (chat.status === 'running') {
        chat.status = 'failed';
        chat.push({ kind: 'error', role: 'engine', text: `运行时意外退出（退出码 ${code}）` });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
      }
      this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
    });

    // stdout is the protocol channel; stderr is free-form launcher/agent noise.
    const rl = readline.createInterface({ input: child.stdout });
    rl.on('line', (line) => this.handleLine(chat, line));
    const rlErr = readline.createInterface({ input: child.stderr });
    rlErr.on('line', (line) => this.handleStderr(chat, line));

    // initialize fixes cwd/provider/model for the lifetime of the process.
    try {
      await chat.request(
        'initialize',
        { cwd: chat.cwd, provider: chat.provider, model: chat.model },
        INIT_TIMEOUT_MS,
      );
    } catch (err) {
      const message = String(err?.message ?? err);
      if (!patch && /no adapter registered for provider/.test(message)) {
        throw new Error(
          `${message}｜这个 provider 不在 TermDesk 写的覆盖配置里；` +
          'TERMDESK_CHAT_PROVIDER / TERMDESK_CHAT_MODEL / TERMDESK_DSH_BASE_URL / ' +
          'TERMDESK_DSH_API / TERMDESK_DSH_API_KEY_ENV 可以指定它',
        );
      }
      throw err;
    }
    // A chat opened from history takes ownership HERE, at the first real turn,
    // not when the transcript was shown. DSH refuses `session/resume` while another
    // process holds the write handle, and that refusal is the honest answer: the
    // desktop application is still in that conversation.
    if (chat.pendingResume) {
      try {
        await chat.request('session/resume', { sessionId: chat.sessionId }, INIT_TIMEOUT_MS);
        chat.pendingResume = false;
      } catch (err) {
        const message = String(err?.message ?? err);
        this.dispose(chat, 'resume-refused');
        throw new Error(
          `无法接管这次会话：${message}。它很可能正由桌面端 DeepSeek Harness 打开，`
          + '那里的对话框关掉后再试，或直接在那里继续。',
        );
      }
      chat.push({ kind: 'local', role: 'engine', text: '已接管本机 DSH 会话 · 后续消息延续原上下文' });
    }
    chat.ready = true;
    chat.lastError = null;
    chat.push({ kind: 'local', role: 'engine', text: '运行时已就绪（原生会话）' });
  }

  /** Route one inbound JSON-RPC frame. */,

  handleLine(chat, line) {
    const trimmed = line.trim();
    if (trimmed.length === 0) return;
    if (trimmed[0] !== '{') {
      // The launcher writes a banner on stdout before protocol frames.
      if (!trimmed.startsWith('[ctrl-immune]')) {
        chat.lineCount += 1;
      }
      return;
    }

    let frame;
    try {
      frame = JSON.parse(trimmed);
    } catch {
      return;
    }

    // A response settles its pending request.
    if (frame.id !== undefined && frame.method === undefined) {
      const slot = chat.pending.get(frame.id);
      if (!slot) return;
      chat.pending.delete(frame.id);
      clearTimeout(slot.timer);
      if (frame.error) {
        slot.reject(new Error(`${slot.method} 失败：${frame.error.message ?? frame.error.code}`));
      } else {
        slot.resolve(frame.result);
      }
      return;
    }

    if (frame.method === 'session.event') {
      this.handleSessionEvent(chat, frame.params);
      return;
    }
    if (frame.method === 'session.status') {
      const status = frame.params?.status;
      if (status === 'idle' && chat.status === 'running') {
        chat.status = 'idle';
        // 产物兜底：内核自己写出来的文件不走写文件工具，回合结束时扫一遍工作目录。
        this.settleProducedFiles(chat);
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'idle' });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'idle' });
        this.notify(this.turnNotification(chat, 'ended'));
        // The runtime is free again, so a message typed during the turn can go now.
        this.drainQueue(chat).catch(() => {});
      } else if (status === 'running' && chat.status !== 'running') {
        chat.status = 'running';
        chat.turnStartedAt = Date.now();
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
      }
      return;
    }
    // subagent.started / subagent.finished and anything else the runtime adds.
    if (typeof frame.method === 'string') {
      const record = chat.push({
        kind: 'engine_note',
        role: 'engine',
        text: frame.method,
        meta: frame.params ?? null,
      });
      this.emitEvent(chat, record);
    }
  },

  handleStderr(chat, line) {
    const trimmed = line.trim();
    if (trimmed.length === 0) return;
    if (trimmed.startsWith('[ctrl-immune]')) return; // launcher banner
    // The runtime reports operational failures here; keep them visible but
    // never as conversation content.
    const record = chat.push({ kind: 'engine_log', role: 'engine', text: trimmed.slice(0, 1000) });
    this.emitEvent(chat, record);
  }

  /**
   * A streamed `assistant/chunk` needs per-chat aggregation state: deltas
   * arrive one token group at a time and must be coalesced into one visible
   * message. Everything else reuses the shared DSH normaliser, so live and
   * stored views cannot drift apart.
   */,

  handleSessionEvent(chat, params) {
    const ev = params?.event;
    if (!ev) return;

    if (process.env.TERMDESK_CHAT_DEBUG === '1') {
      const summary = ev.type === 'assistant/chunk'
        ? `${ev.type} ${ev.data?.chunk?.type ?? ''} idx=${ev.data?.chunk?.index ?? ''}`
        : `${ev.type}${ev.data?.source?.kind ? ` source=${ev.data.source.kind}` : ''}`;
      console.error(`[chat ${chat.id}] ${summary}`);
    }

    if (ev.type === 'assistant/chunk') {
      this.handleAssistantChunk(chat, ev);
      return;
    }

    // The runtime echoes the user's own message back as a `user/message` event.
    // That copy is authoritative in content but arrives after the injected
    // context, so keeping it would place the user's words below context blocks
    // and show them twice. When it matches the optimistic line already on
    // screen, the runtime copy is dropped and the original position kept.
    if (ev.type === 'user/message') {
      const normalized = dshEventToChatEvent(ev);
      if (normalized && normalized.role === 'user') {
        const pending = chat.pendingUserEcho;
        if (pending && pending.text.trim() === normalized.text.trim()) {
          chat.pendingUserEcho = null;
          // Tell the client the optimistic line is now confirmed, so it can
          // stop showing it as pending. Nothing is removed: it is the same line.
          pending.optimistic = false;
          this.emitEvent(chat, pending);
          return;
        }
      }
      // Anything else (unexpected text, or injected context) flows through the
      // shared normaliser as usual.
      if (normalized) this.pushAndEmit(chat, normalized);
      return;
    }

    // A completed assistant message is authoritative: the deltas were only a
    // preview of it. Drop the streamed answer previews and keep the final text,
    // so the answer is shown once instead of twice.
    if (ev.type === 'assistant/message') {
      const normalized = dshEventToChatEvent(ev);
      if (ev.data?.usage) chat.usage = ev.data.usage;
      chat.streaming = null;
      if (!normalized) {
        this.discardPreviews(chat, 'message');
        this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
        return;
      }
      if (normalized.kind === 'message') {
        this.discardPreviews(chat, 'message');
      }
      this.pushAndEmit(chat, normalized);
      return;
    }

    if (ev.type === 'turn/end') {
      // A turn that produced nothing must say so. Without this the phone showed a
      // turn that started and ended with no text and no error - indistinguishable
      // from a broken app, when the truth was that the runtime's own request/answer
      // path returned nothing. Measured on the phone: the model answers fine from
      // the same sandbox (direct API call, content "OK"), so an empty turn here is
      // a fact about the runtime's turn, and it is reported as one.
      const userSeq = chat.events.filter((e) => e.kind === 'message' && e.role === 'user').pop();
      const produced = chat.events.some((e) => (!userSeq || e.seq > userSeq.seq)
        && (e.kind === 'message' || e.kind === 'tool' || e.kind === 'tool_result' || e.kind === 'reasoning'));
      const emptyReason = ev.data?.reason?.kind ?? null;
      if (!produced) {
        const tail = String(chat.stderrTail ?? '').replace(/\s+/g, ' ').trim().slice(-300);
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: tail
            ? `DSH 这一轮没有返回任何内容（原因：${emptyReason ?? '未说明'}）。运行时最后的输出：${tail}`
            : `DSH 这一轮没有返回任何内容（原因：${emptyReason ?? '未说明'}），运行时也没有给出原因。`,
          name: 'warning',
        });
      }
      chat.streaming = null;
      // Any preview still open when the turn ends was never confirmed by a
      // final message; keep it as the record rather than dropping real output.
      for (const preview of chat.previews) preview.streaming = false;
      chat.previews = [];
      const reason = ev.data?.reason?.kind ?? null;
      const record = chat.push({ kind: 'turn', role: 'engine', text: '', meta: { state: 'ended', reason } });
      this.emitEvent(chat, record);
      this.emit({ event: 'chat.turn', chatId: chat.id, state: 'ended', reason });
      return;
    }

    const normalized = dshEventToChatEvent(ev);
    if (normalized) this.pushAndEmit(chat, normalized);
  },

  handleAssistantChunk(chat, ev) {
    return handleStreamChunk(chat, ev, {
      push: (target, event) => target.push(event),
      emitEvent: (target, record, extra) => this.emitEvent(target, record, extra),
    });
  },

  pushAndEmit(chat, normalized) {
    const record = chat.push(normalized);
    this.emitEvent(chat, record);
    return record;
  },

  emitEvent(chat, record, extra = {}) {
    this.emit({ event: 'chat.event', chatId: chat.id, seq: record.seq, item: record, ...extra });
  },

  discardPreviews(chat, kind) {
    return discardStreamedPreviews(chat, kind, this);
  },

  failPending(chat, error) {
    for (const [id, slot] of [...chat.pending]) {
      chat.pending.delete(id);
      clearTimeout(slot.timer);
      slot.reject(error);
    }
  }

  /** Terminate a chat's runtime/turn and cancel its in-flight request. */,

  dispose(chat, _reason) {
    this.stopLiveTail(chat);
    this.failPending(chat, new Error('会话已结束'));
    // A kernel that owns its session store is stopped by asking it to stop, and the
    // conversation stays resumable: nothing of ours is running to kill.
    if (this.capabilitiesOf(chat.engine).ownsSessionStore) {
      this.clearCodexWatchdog(chat);
      if (chat.threadId) {
        this.codexThreads.delete(chat.threadId);
        if (chat.status === 'running') this.codex?.interruptTurn(chat.threadId).catch(() => {});
      }
      chat.ready = false;
      return;
    }
    // Everything below is the SDK runtime, which is a child process of ours.
    const child = chat.child;
    chat.child = null;
    chat.ready = false;
    if (!child || child.exitCode !== null) return;

    try {
      // Ask politely first: the runtime disposes its root context on `shutdown`.
      const id = String(chat.nextRpcId++);
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method: 'shutdown' })}\n`);
    } catch {
      // stdin already gone; fall through to the hard kill.
    }
    const pid = child.pid;
    setTimeout(() => {
      if (child.exitCode !== null) return;
      try {
        if (process.platform === 'win32' && pid) {
          spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
        } else {
          child.kill('SIGKILL');
        }
      } catch {
        try { child.kill(); } catch { /* already gone */ }
      }
    }, 1500).unref?.();
  },
};
