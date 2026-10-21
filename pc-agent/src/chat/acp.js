/** ChatManager acp-turn methods; mixed onto the prototype by ../chat.js. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { AcpKernel, acpSessionToSessionInfo, acpUpdateToChatEvents } from '../kernels/acp.js';
import { CliKernel } from '../kernels/cli.js';
import { routeConfig, routeConfigError } from '../kernels/dsh.js';
import { discoverLiveHost, liveListCommands } from '../kernels/dsh-live.js';
import { isAcpKernel, isCliKernel, shimSpec, spawnSpec } from '../kernels/registry.js';
import { lastWriteOf, readSession } from '../sessions.js';
import { unknownKernelMessage } from '../kernels/contract.js';
import { Chat } from './chat.js';
import { MAX_CHATS, lookupKernel } from './shared.js';

export const acpMethods = {
  kernelFor(engineId) {
    let kernel = this.acp.get(engineId);
    if (kernel) return kernel;
    const spec = spawnSpec(engineId);
    if (!spec) throw new Error(`找不到 ACP 内核 "${engineId}" 的可执行文件`);
    if (isCliKernel(engineId)) {
      // A CLI-shaped kernel: one adapter, driven by its manifest. Updates are
      // emitted in the ACP shape, so everything downstream is the same code.
      const manifest = shimSpec(engineId);
      if (!manifest) throw new Error('内核没有 shim manifest');
      // spec.args is the prelude (node + script for a node-hosted CLI).
      const cli = new CliKernel({ id: engineId, bin: spec.bin, preArgs: spec.args, manifest, log: (line) => console.log(line) });
      cli.on('update', (sessionId, update) => this.handleAcpUpdate(engineId, sessionId, update));
      cli.on('exit', () => this.acp.delete(engineId));
      this.acp.set(engineId, cli);
      return cli;
    }
    kernel = new AcpKernel({ id: engineId, bin: spec.bin, args: spec.args, log: (line) => console.log(line) });
    kernel.on('update', (sessionId, update) => this.handleAcpUpdate(engineId, sessionId, update));
    kernel.on('permission', (info) => this.handleAcpPermission(engineId, info));
    // The kernel's command lines are ours (see kernels/acp-terminal.js), so what
    // it runs shows up in the conversation it belongs to.
    kernel.on('terminal', (event) => this.handleAcpTerminal(engineId, event));
    kernel.on('serverRequest', (method) => {
      console.error(`[acp:${engineId}] 引擎请求未实现，已拒绝：${method}`);
    });
    kernel.on('exit', (code) => {
      for (const chat of this.chats.values()) {
        if (chat.engine !== engineId || chat.status !== 'running') continue;
        chat.status = 'failed';
        chat.lastError = `${engineId} 内核进程退出（${code}）`;
        chat.push({ kind: 'error', role: 'engine', text: chat.lastError });
        this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
        this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
      }
      this.acp.delete(engineId);
    });
    this.acp.set(engineId, kernel);
    return kernel;
  }

  /**
   * Open this chat's kernel-side session, once, and remember what that session
   * says it can become.
   *
   * Shared by the turn path and by "which models can this conversation use",
   * because both need the SAME session: asking for the model list must not open
   * a second one, and must not leave an empty one behind in the kernel's own
   * history either - the session opened here is the one the first turn uses.
   */,

  async openAcpSession(chat, kernel) {
    const sessionId = await kernel.newSession({ cwd: chat.cwd });
    // A CLI kernel names its session, so this can be empty: the id arrives with
    // the first turn. Routing is only keyed once there is something to key on -
    // an empty map key would catch another chat's updates.
    chat.sessionId = sessionId;
    chat.nativeResume = true;
    chat.ready = true;
    // What this kernel says the conversation can be switched to. The phone
    // offers exactly this list, so a model choice is never a guess.
    chat.availableModels = kernel.availableModels(sessionId);
    const options = kernel.sessionOptions?.(sessionId) ?? null;
    chat.availableModes = options?.modes ?? null;
    chat.mode = options?.modes?.currentModeId ?? null;
    if (sessionId) this.acpSessions.set(sessionId, chat);
    return sessionId;
  }

  /** This chat's kernel, started, with a live session on it. */,

  async ensureAcpSession(chat) {
    const kernel = this.kernelFor(chat.engine);
    await kernel.ensureStarted();
    if (!chat.ready || !chat.nativeResume) await this.openAcpSession(chat, kernel);
    return kernel;
  }

  /**
   * What this conversation can be switched to.
   *
   * ACP declares its models when a session exists, and TermDesk keeps chats lazy,
   * so the list is asked for when the phone opens the picker instead of at
   * creation: the session that answers this question is the session the first
   * turn then runs on, so nothing is opened twice and no empty conversation is
   * left in the kernel's history.
   *
   * A kernel with no model list (a CLI shim, DSH) says so instead of answering
   * with an empty list that would look like "no models available".
   */
  /**
   * The slash commands this conversation can run.
   *
   * Only a live-attached DSH conversation has a real registry: the commands belong
   * to the desktop host that owns the session. Everything else says so instead of
   * returning an empty list that looks like "no commands exist".
   */,

  async commandsFor(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (chat.engine === 'dsh' && chat.liveHost) {
      const listed = await liveListCommands(chat.liveHost, chat.sessionId);
      if (!listed.ok) {
        return { ok: true, chatId: id, supported: false, commands: [], note: listed.message ?? '桌面端没有返回命令目录' };
      }
      return { ok: true, chatId: id, supported: true, commands: listed.commands, note: null };
    }
    return {
      ok: true,
      chatId: id,
      supported: false,
      commands: [],
      note: chat.engine === 'dsh'
        ? '桌面版 DeepSeek Harness 没有在运行：/compact 等命令由它执行'
        : '这个助手没有提供斜杠命令',
    };
  },

  async modelsFor(id) {
    const chat = this.chats.get(id);
    if (!chat) return { ok: false, code: 'no_chat', message: '会话不存在' };
    if (!isAcpKernel(chat.engine)) {
      return {
        ok: true,
        chatId: id,
        supported: false,
        current: chat.model ?? null,
        models: [],
        modes: null,
        message: `${chat.engine} 内核不由 ACP 提供模型清单`,
      };
    }
    try {
      const kernel = await this.ensureAcpSession(chat);
      const info = chat.availableModels ?? kernel.availableModels(chat.sessionId);
      chat.availableModels = info;
      const options = kernel.sessionOptions?.(chat.sessionId) ?? null;
      return {
        ok: true,
        chatId: id,
        sessionId: chat.sessionId ?? null,
        supported: true,
        current: info?.current ?? null,
        models: (info?.models ?? []).map((m) => ({ id: m.id, label: m.label ?? m.id })),
        // Declared permission/agent modes ride along: they are part of the same
        // session declaration, and the phone's permission switch needs them.
        modes: options?.modes ?? null,
      };
    } catch (err) {
      return {
        ok: false,
        code: 'session_failed',
        message: `${chat.engine} 无法读取模型清单：${String(err?.message ?? err)}`,
      };
    }
  }

  /** One ACP turn: attach (or open) the session, then prompt. */,

  async sendAcp(chat, message, userSeq) {
    let kernel;
    try {
      kernel = this.kernelFor(chat.engine);
      await kernel.ensureStarted();
    } catch (err) {
      return this.failAcp(chat, `无法启动 ${chat.engine} 内核：${String(err?.message ?? err)}`, userSeq, 'spawn_failed');
    }

    if (!chat.ready || !chat.nativeResume) {
      try {
        await this.openAcpSession(chat, kernel);
      } catch (err) {
        return this.failAcp(chat, `${chat.engine} 无法创建会话：${String(err?.message ?? err)}`, userSeq, 'session_failed');
      }
    }

    // A model set on the chat (or sent with this turn) has to reach the kernel;
    // otherwise the picker would be decoration. Once per change, not per turn.
    if (chat.model && chat.appliedModel !== chat.model) {
      try {
        await kernel.setModel(chat.sessionId, chat.model);
        chat.appliedModel = chat.model;
      } catch (err) {
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: `无法切换模型（${chat.model}）：${String(err?.message ?? err)}`,
          name: 'warning',
        });
      }
    }

    chat.status = 'running';
    chat.kernelLive = true;
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'running' });
    this.emitEvent(chat, userSeq);

    try {
      const { stopReason, sessionId: namedSession } = await kernel.prompt(chat.sessionId, message);
      // A kernel that names its own session (every CLI shim) hands the id back
      // here; adopting it is what makes the NEXT turn a resume instead of a
      // new conversation.
      if (namedSession && namedSession !== chat.sessionId) {
        chat.sessionId = namedSession;
        chat.nativeSessionId = namedSession;
        this.acpSessions.set(namedSession, chat);
      }
      // A kernel can conclude a turn having said nothing at all — OpenCode does
      // exactly that when its provider refuses the call (measured: the free tier
      // is refused from third-party clients, and the ACP层 answers end_turn with
      // zero tokens instead of an error). Silence is indistinguishable from a
      // broken product, so say what happened and quote the kernel's own output.
      const produced = chat.events.some(
        (e) => e.seq > userSeq.seq && (e.kind === 'message' || e.kind === 'tool' || e.kind === 'tool_result' || e.kind === 'reasoning'),
      );
      if (!produced) {
        const tail = String(kernel.stderrTail ?? '').replace(/\s+/g, ' ').trim().slice(-300);
        chat.push({
          kind: 'engine_note',
          role: 'engine',
          text: tail
            ? `内核这一轮没有返回任何内容（stopReason=${stopReason ?? '未知'}）。内核自己的最后输出：${tail}`
            : `内核这一轮没有返回任何内容（stopReason=${stopReason ?? '未知'}），也没有给出原因。这通常意味着内核内部的模型调用被拒绝或额度受限。`,
          name: 'warning',
        });
      }
      this.finishAcpTurn(chat, stopReason);
      return { ok: true, userSeq, messageId: null, sessionId: chat.sessionId };
    } catch (err) {
      // A rejected prompt is reported after the streamed partial answer, so the
      // transcript keeps whatever the kernel already produced.
      return this.failAcp(chat, `${chat.engine} 调用失败：${String(err?.message ?? err)}`, userSeq, 'prompt_failed');
    }
  },

  failAcp(chat, message, userSeq, code) {
    chat.status = 'failed';
    chat.lastError = message;
    chat.push({ kind: 'error', role: 'engine', text: message });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: 'failed' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: 'failed' });
    return { ok: false, code, message, userSeq };
  }

  /**
   * Refuse a turn on a kernel this build cannot drive.
   *
   * Reached when the table has no driver for the engine — an id from a newer phone,
   * or a typo. It fails the turn LOUDLY and names the engine: the alternative, which
   * this replaced, was falling through to the SDK driver and talking a protocol the
   * kernel may not speak, or doing nothing at all.
   */,

  failUnsupported(chat, userSeq) {
    const message = unknownKernelMessage(chat.engine, lookupKernel)
      ?? `${chat.engine} 内核没有可用的驱动方式，请更新电脑端 TermDesk`;
    return this.failAcp(chat, message, userSeq, 'unsupported_kernel');
  }

  /**
   * Open a past ACP session: the kernel replays its transcript as
   * session/update notifications and the next message continues it. This is
   * why history and live chat are one path — the same session id backs both.
   */,

  async resumeAcp(engine, id) {
    const adopted = [...this.chats.values()].find((c) => c.engine === engine && c.sessionId === id);
    if (adopted) return { ok: true, chat: adopted.detail() };

    let kernel;
    try {
      kernel = this.kernelFor(engine);
      await kernel.ensureStarted();
    } catch (err) {
      return { ok: false, code: 'engine_unavailable', message: String(err?.message ?? err) };
    }

    // The working directory belongs to the session, so it is read from the
    // kernel's own index rather than guessed.
    let info = null;
    try {
      const listed = await kernel.listSessions();
      info = listed.sessions.find((s) => (s.sessionId ?? s.id) === id) ?? null;
    } catch { /* a kernel without session/list still allows load */ }

    const cwd = info?.cwd ?? null;
    if (cwd && (!fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory())) {
      return { ok: false, code: 'missing_workspace', message: '原会话的工作目录不存在，不能静默切换到其他目录' };
    }

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: info?.title || (info?.cwd ? path.basename(info.cwd) : '历史对话'),
      cwd: cwd ?? os.homedir(),
      engine,
      provider: null,
      model: null,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    chat.ready = true;
    chat.kernelLive = true;
    if (info?.updatedAt) {
      const at = Date.parse(info.updatedAt);
      if (!Number.isNaN(at)) chat.createdAt = at;
    }
    this.chats.set(chat.id, chat);
    // Routing must be in place BEFORE loadSession: the replay arrives as
    // notifications that precede the response.
    this.acpSessions.set(id, chat);

    try {
      await kernel.loadSession(id, { cwd: chat.cwd });
    } catch (err) {
      this.acpSessions.delete(id);
      this.chats.delete(chat.id);
      return { ok: false, code: 'session_busy', message: `内核无法打开该会话：${String(err?.message ?? err)}` };
    }
    this.discardPreviews(chat, 'message');
    this.discardPreviews(chat, 'reasoning');
    chat.push({
      kind: 'local',
      role: 'engine',
      text: kernel.canReplay === false
        ? '已接上该内核的会话 · 后续消息延续原上下文（该内核不提供历史正文，所以这里没有回放）'
        : '已恢复内核原生会话 · 后续消息延续原上下文',
    });
    return { ok: true, chat: chat.detail() };
  }

  /**
   * Open a recorded DSH session on its own runtime.
   *
   * The SDK runtime loads the persisted history itself (`session/resume`), so the
   * next prompt continues the same conversation: nothing is reconstructed from the
   * display transcript, and no text is ever re-fed as a prompt.
   *
   * Two facts are stated rather than guessed:
   *   - the working directory comes from the stored session header, because
   *     resuming a conversation in a different tree would silently change what its
   *     relative paths mean;
   *   - the model route comes from this machine's explicit DSH configuration, and
   *     the runtime applies it to the resumed agent (the protocol says so); the
   *     session's own last route is not reversible from the log.
   *
   * The desktop application may be holding the same session at the same time. The
   * persistence lock is per process, so this cannot be prevented from here — the
   * caller has already been told via `takeoverNote` that two writers can diverge.
   */,

  async resumeDsh(engine, id) {
    let detail = null;
    try {
      detail = await readSession({ engine: 'dsh', id });
    } catch {
      detail = null;
    }
    const cwd = detail?.meta?.cwd || null;
    if (!cwd || !fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory()) {
      return {
        ok: false,
        code: 'missing_workspace',
        message: '原会话的工作目录不存在或无法读取，不能静默切换到其他目录',
      };
    }

    let route;
    try {
      route = routeConfig(process.env, {});
      const routeError = routeConfigError(route);
      if (routeError) return { ok: false, code: 'unconfigured', message: routeError };
    } catch (err) {
      return { ok: false, code: 'unconfigured', message: String(err?.message ?? err) };
    }

    this.dropIdleChat();
    if (this.chats.size >= MAX_CHATS) return { ok: false, code: 'capacity', message: '运行中的会话已达上限' };

    const chat = new Chat({
      id: `c-${crypto.randomUUID()}`,
      title: detail?.meta?.title || path.basename(cwd),
      cwd,
      engine,
      provider: route.provider,
      model: route.model,
      nativeSessionId: id,
    });
    chat.titleSource = 'recorded';
    // Opening a recorded session must not TAKE the session. DSH's `session/resume`
    // opens a write handle, and it is refused while any other process (the desktop
    // app, most of the time) holds one — so attaching here would make "just look at
    // the history" fail, and, worse, would take ownership away from the app that is
    // showing the conversation right now. The handle is therefore taken when the
    // first message is actually sent (see ensureRuntime).
    chat.pendingResume = true;
    this.chats.set(chat.id, chat);

    // The recorded conversation is shown so the phone opens a conversation rather
    // than an empty page. It is display state only: the runtime owns the prompt
    // history, and this transcript is never sent to a model.
    for (const event of detail?.events ?? []) {
      const at = event.at ? Date.parse(event.at) : NaN;
      chat.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: Number.isNaN(at) ? undefined : at,
        name: event.name ?? null,
        meta: event.meta ?? null,
      });
    }
    // How far this transcript is known to be current. `refreshStaleDsh` compares the
    // journal's write time against it, so an unchanged file is never decompressed a
    // second time just because somebody reopened the screen.
    chat.historySeededAt = lastWriteOf({ engine: 'dsh', id })?.at ?? new Date().toISOString();

    // If the desktop app is running, this conversation is ITS to continue: it
    // already holds the write handle, and a second runtime would fork the journal.
    // Messages then go to the live agent through its own API (see sendDshLive),
    // which is the only way to continue the conversation without stealing it.
    const liveHost = discoverLiveHost({ fresh: true });
    if (liveHost) {
      chat.liveHost = liveHost;
      chat.kernelLive = true;
      chat.ready = true;
      // Mirror the conversation from the moment of attaching: messages typed on
      // the desktop must arrive here too, and the phone must never be the reason a
      // second writer exists.
      this.startLiveWatch(chat, id);
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已接入桌面版 DeepSeek Harness 的实时会话：消息直接进入桌面端的对话，回复也从那里读回',
      });
    } else {
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已打开本机保存的 DSH 会话记录 · 发送下一条消息时接管这次会话',
      });
    }
    return { ok: true, chat: chat.detail() };
  },

  /**
   * Re-read one conversation from its journal and re-attach the mirror.
   *
   * Why this exists: a live-attached chat is not idle — the desktop app keeps writing
   * into the same session while the phone only watches — but the idle reaper only
   * knows `lastUsedAt`, which used to move only when the phone sent something. After
   * 45 minutes of watching, the chat was disposed: the follow stream stopped and the
   * transcript froze at whatever had been mirrored so far, while the chat stayed in
   * the list. The answer that was being written at that moment (and every turn after
   * it) never arrived, and reopening joined the same frozen handle.
   *
   * `refreshStaleDsh` decides WHEN a re-read is worth it (only when the journal has
   * moved); this method performs it. Display state only: the runtime owns the prompt
   * history, and none of this is ever fed back as a prompt.
   */
  async refreshDshChat(chat, { read = readSession, discover = discoverLiveHost } = {}) {
    if (chat.engine !== 'dsh' || !chat.nativeResume) return false;
    // A running turn owns the transcript; replacing it would delete the live answer.
    if (chat.status === 'running') return false;
    let detail = null;
    try { detail = await read({ engine: 'dsh', id: chat.sessionId }); } catch { detail = null; }
    if (!detail) return false;

    this.stopLiveTail(chat);
    // The transcript is replaced, not merged: it is a snapshot of the journal, and a
    // merge would keep rows the engine no longer shows as current.
    chat.events = [];
    chat.seq = 0;
    chat.previews = [];
    chat.streaming = null;
    chat.status = 'idle';
    chat.lastError = null;
    for (const event of detail.events ?? []) {
      const at = event.at ? Date.parse(event.at) : NaN;
      chat.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: Number.isNaN(at) ? undefined : at,
        name: event.name ?? null,
        meta: event.meta ?? null,
      });
    }
    if (detail.meta?.title && chat.titleSource !== 'user') {
      chat.title = detail.meta.title;
      chat.titleSource = 'recorded';
    }
    chat.historySeededAt = lastWriteOf({ engine: 'dsh', id: chat.sessionId })?.at
      ?? new Date().toISOString();
    chat.lastUsedAt = Date.now();

    let liveHost = null;
    try { liveHost = discover(); } catch { liveHost = null; }
    if (liveHost) {
      chat.liveHost = liveHost;
      chat.kernelLive = true;
      chat.pendingResume = false;
      chat.ready = true;
      // Start the mirror from a clean cursor: the numbers from the previous stream
      // describe a stream that no longer exists, and keeping them would dedupe away
      // the first events of the new one.
      chat.liveLastSeq = -1;
      chat.liveSeenSnapshot = false;
      this.startLiveWatch(chat, chat.sessionId);
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已重新读取这段会话的最新记录，并接上桌面端的实时会话',
      });
    } else {
      chat.liveHost = null;
      chat.kernelLive = false;
      chat.ready = false;
      // The desktop app owned the write handle; with it gone the next message has to
      // take the handle over, which `ensureRuntime` only does when this is set.
      chat.pendingResume = true;
      chat.push({
        kind: 'local',
        role: 'engine',
        text: '已重新读取这段会话的最新记录；桌面端不在运行，发送消息时会接管这次会话',
      });
    }
    return true;
  },

  /**
   * Re-read a chat whose mirror was reaped, but only when there is something new.
   *
   * Called from `resume` (the phone opening the conversation) and from `chat.read` /
   * `chat.sync` (a screen that was already open asking again). The journal's write
   * time is the evidence: an unchanged file means the frozen transcript is still the
   * truth, and decompressing a large session on every reconnect would be pure cost.
   */
  async refreshStaleDsh(chat, deps) {
    if (chat.engine !== 'dsh' || !chat.nativeResume) return false;
    if (chat.ready || chat.status === 'running') return false;
    const write = lastWriteOf({ engine: 'dsh', id: chat.sessionId });
    if (!write) return false;
    // `historySeededAt` is absent on chats seeded by older builds: they cannot prove
    // they are current, so they get one re-read rather than a permanent freeze.
    if (chat.historySeededAt) {
      const seeded = Date.parse(chat.historySeededAt);
      if (Number.isFinite(seeded) && Date.parse(write.at) <= seeded) return false;
    }
    return this.refreshDshChat(chat, deps);
  },

  /** Route one session/update notification into the right transcript. */

  handleAcpUpdate(engineId, sessionId, update) {
    // Session-id routing, with one honest fallback: a CLI kernel usually names
    // its session only at the end, so updates that arrive before the id is known
    // belong to the one turn this engine has running.
    const chat = (sessionId ? this.acpSessions.get(sessionId) : null)
      ?? [...this.chats.values()].find((c) => c.engine === engineId && c.status === 'running')
      ?? null;
    if (!chat || chat.engine !== engineId) return;
    if (chat.status === 'stopped') return;

    for (const event of acpUpdateToChatEvents(update)) {
      if (event.kind === 'message' && event.role === 'user') {
        if (update.sessionUpdate === 'user_message_chunk') {
          // Live turn: the phone already shows the optimistic line, so confirm
          // it instead of appending a duplicate. Replay (load): there is no
          // optimistic line, so the replayed chunk IS the record.
          const pending = chat.pendingUserEcho;
          if (pending && pending.text.trim() === event.text.trim()) {
            chat.pendingUserEcho = null;
            pending.optimistic = false;
            this.emitEvent(chat, pending);
          } else if (!pending) {
            this.pushAndEmit(chat, { kind: 'message', role: 'user', text: event.text });
          }
        }
        continue;
      }
      if (event.streaming) {
        // ACP streams the answer as chunks and NEVER sends a finished copy of it,
        // so the chunks ARE the message. Consecutive chunks of the same kind are
        // coalesced into one record (same seq, growing text) and the phone is
        // told to replace it; there is nothing to discard at the end of the turn.
        // Treating them as previews instead deleted the whole answer — the phone
        // showed the tool rows and not one word of text, which is the bug that
        // was reported from the device.
        const last = chat.events[chat.events.length - 1];
        if (last && last.streaming && last.kind === event.kind && last.role === event.role) {
          last.text += event.text;
          this.emitEvent(chat, last, { stream: true });
          continue;
        }
        const record = chat.push(event);
        this.emitEvent(chat, record, { stream: true });
        continue;
      }
      if (event.kind === 'message' || event.kind === 'reasoning') this.discardPreviews(chat, event.kind);
      this.pushAndEmit(chat, event);
    }
  }

  /** Close out an ACP turn exactly once, mirroring the Codex lifecycle. */,

  finishAcpTurn(chat, stopReason) {
    if (chat.status === 'stopped') return;
    const cancelled = stopReason === 'cancelled';
    const failed = stopReason === 'refusal';
    // Everything streamed during this turn is finished text now, so the cursor
    // stops. (There are no previews to discard on this path: ACP chunks are the
    // message, not a preview of one.)
    for (const record of chat.events.filter((e) => e.streaming)) {
      record.streaming = false;
      this.emitEvent(chat, record, { stream: false });
    }
    chat.status = failed ? 'failed' : 'idle';
    chat.lastError = failed ? '内核拒绝了本轮请求' : null;
    chat.push({
      kind: 'turn', role: 'engine', text: '',
      meta: { state: failed ? 'failed' : 'ended', reason: cancelled ? 'interrupted' : (stopReason ?? null) },
    });
    this.emit({ event: 'chat.turn', chatId: chat.id, state: failed ? 'failed' : 'ended' });
    this.emit({ event: 'chat.status', chatId: chat.id, status: chat.status });
    this.notify(this.turnNotification(chat, failed ? 'failed' : 'ended'));
    // The turn is over, so anything typed during it can start now. A failed turn is not
    // drained: the next message would go into the same wall.
    if (!failed) this.drainQueue(chat).catch(() => {});
  }

  /**
   * The kernel's own session index for one ACP engine, in the phone's
   * SessionInfo shape. History and live chat share the identity, so an entry
   * listed here is exactly one that resumeAcp can open.
   */,

  async listAcpSessions(engineId, { cwd } = {}) {
    const kernel = this.kernelFor(engineId);
    await kernel.ensureStarted();
    const { supported, sessions } = await kernel.listSessions({ cwd });
    if (!supported) return [];
    return sessions.map((s) => acpSessionToSessionInfo(engineId, s)).filter(Boolean);
  }

  /**
   * Read one ACP session's transcript WITHOUT attaching a chat to it.
   *
   * History viewing and history opening are different intents: the phone shows
   * a transcript read-only until the user chooses to continue it. Replaying the
   * kernel's own transcript is the only way to show the truth, and the events
   * are folded here exactly as they are folded live (chunks merge into the
   * message they build), so a replayed answer looks like the original one.
   */,

  async readAcpSession(engineId, id) {
    const kernel = this.kernelFor(engineId);
    await kernel.ensureStarted();

    let info = null;
    try {
      const listed = await kernel.listSessions();
      info = listed.sessions.find((s) => (s.sessionId ?? s.id) === id) ?? null;
    } catch { /* a kernel without session/list can still load */ }

    const events = [];
    const push = (event) => {
      events.push({
        kind: event.kind,
        role: event.role ?? null,
        text: event.text ?? '',
        at: new Date().toISOString(),
        name: event.name ?? null,
        state: event.meta?.state ?? null,
        exitCode: event.meta?.exitCode ?? null,
        tokens: null,
      });
    };
    const listener = (sessionId, update) => {
      if (sessionId !== id) return;
      for (const event of acpUpdateToChatEvents(update)) {
        // A replayed transcript is authoritative, so streamed chunks are folded
        // into the message they are building instead of being kept as previews.
        const last = events[events.length - 1];
        if (event.streaming && last && last.kind === event.kind && last.role === event.role) {
          last.text += event.text;
          continue;
        }
        push(event);
      }
    };

    kernel.on('update', listener);
    try {
      await kernel.loadSession(id, { cwd: info?.cwd });
    } finally {
      kernel.off('update', listener);
    }

    return {
      meta: {
        engine: engineId,
        id,
        cwd: info?.cwd ?? null,
        createdAt: info?.createdAt ?? null,
        title: info?.title ?? null,
        filePath: null,
      },
      events,
      totalEvents: events.length,
      truncated: false,
    };
  }

  /**
   * Stop the in-flight turn.
   *
   * dsh: there is no wire cancel, so this disposes the runtime (the
   *   conversation itself ends — reported honestly, not as a soft abort).
   * codex: the turn is one `codex exec` process, so killing it stops the
   *   reply while the Codex thread stays resumable for the next message.
   */,
};
