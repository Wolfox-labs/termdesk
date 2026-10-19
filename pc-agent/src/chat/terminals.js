/** ChatManager terminals-turn methods; mixed onto the prototype by ../chat.js. */
import { collectsLiveTerminals } from '../kernels/contract.js';
import { lookupKernel } from './shared.js';

export const terminalMethods = {
  async chatTerminals(chatId) {
    const chat = this.chats.get(chatId);
    if (!chat) return null;
    this.terminalWatch.add(chatId);
    // Codex keeps the background terminals of a thread on its own side, so the
    // kernel is asked. That is what makes the list "what is still running on the
    // PC" instead of "what happened while this phone happened to be connected".
    // "进行中" promises what is STILL RUNNING on the PC. Only the app-server kernel
    // can be asked that; asking the others would spend a round trip on an answer
    // that does not exist.
    if (collectsLiveTerminals(chat.engine, lookupKernel)) {
      await this.refreshCodexTerminals(chat).catch(() => {});
    }
    return { chatId, terminals: chat.terminalList() };
  },

  chatTerminalRead(chatId, terminalId) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminalDetail(terminalId);
    if (!chat || !record) return null;
    this.terminalWatch.add(chatId);
    // `terminalId` on the wire, `id` in the record: the field the client sent is
    // the field it gets back, so a frame can never be matched against the wrong
    // key (this was silently mismatched once already).
    return { chatId, terminalId: record.id, ...record };
  },

  async chatTerminalWrite(chatId, terminalId, data) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminals.get(terminalId);
    if (!chat || !record) throw new Error('没有这个终端');
    if (record.origin !== 'kernel') {
      // The refusal has to be specific: "the kernel runs this one itself, and
      // the protocol has no write channel for it" is something a user can act on.
      throw new Error('这条命令由内核自己执行，协议没有提供写入通道；只能看输出或终止它');
    }
    const kernel = this.kernelFor(chat.engine);
    const ok = kernel.terminals?.write?.(terminalId, String(data ?? ''));
    if (!ok) throw new Error('终端已经结束了');
    return { chatId, terminalId, wrote: true };
  },

  async chatTerminalStop(chatId, terminalId) {
    const chat = this.chats.get(chatId);
    const record = chat?.terminals.get(terminalId);
    if (!chat || !record) throw new Error('没有这个终端');
    if (record.state !== 'running') return { chatId, terminalId, stopped: false, reason: '已经结束' };
    if (record.origin === 'kernel') {
      this.kernelFor(chat.engine).terminals?.kill({ terminalId });
      return { chatId, terminalId, stopped: true };
    }
    if (!chat.threadId || !record.processId) throw new Error('这条命令不在可终止的后台终端清单里');
    await this.codexServer().call('thread/backgroundTerminals/terminate', {
      threadId: chat.threadId,
      processId: record.processId,
    });
    chat.finishTerminal(terminalId, { exitCode: null });
    this.emitTerminalList(chat);
    return { chatId, terminalId, stopped: true };
  }

  /** Merge Codex's own list of a thread's background terminals into the record. */,

  async refreshCodexTerminals(chat) {
    const result = await this.codexServer().call('thread/backgroundTerminals/list', { threadId: chat.threadId });
    const data = Array.isArray(result?.data) ? result.data : [];
    let changed = false;
    for (const entry of data) {
      const id = entry?.itemId ?? entry?.processId;
      if (!id) continue;
      const existing = chat.terminals.get(id);
      if (existing) {
        if (!existing.processId && entry.processId) existing.processId = entry.processId;
        continue;
      }
      chat.startTerminal({
        id,
        origin: 'agent',
        command: entry.command ?? '',
        cwd: entry.cwd ?? chat.cwd ?? null,
        processId: entry.processId ?? null,
      });
      changed = true;
    }
    if (changed || data.length > 0) this.emitTerminalList(chat);
    return data.length;
  }

  /**
   * ACP terminal events: the kernel asked us to run something, or that process
   * produced output / exited / was released.
   */,

  handleAcpTerminal(engineId, event) {
    const sessionId = event?.sessionId ?? event?.terminal?.sessionId ?? null;
    const chat = sessionId ? this.acpSessions.get(sessionId) : null;
    if (!chat) return;
    if (event.type === 'created' && event.terminal?.id) {
      chat.startTerminal({
        id: event.terminal.id,
        origin: 'kernel',
        command: event.terminal.command ?? '',
        cwd: event.terminal.cwd ?? chat.cwd ?? null,
      });
      this.emitTerminalList(chat);
      return;
    }
    const terminalId = event.terminalId ?? event.terminal?.id;
    if (!terminalId) return;
    if (event.type === 'output') {
      chat.appendTerminal(terminalId, event.chunk ?? '');
      if (this.terminalWatch.has(chat.id)) {
        this.emit({ event: 'chat.terminal.output', chatId: chat.id, terminalId, chunk: event.chunk ?? '' });
      }
      return;
    }
    if (event.type === 'input' && this.terminalWatch.has(chat.id)) {
      this.emit({ event: 'chat.terminal.input', chatId: chat.id, terminalId, data: event.data ?? '' });
      return;
    }
    if (event.type === 'exited') {
      chat.finishTerminal(terminalId, { exitCode: event.exitCode ?? null });
      this.emitTerminalList(chat);
      return;
    }
    if (event.type === 'released') {
      chat.terminals.delete(terminalId);
      this.emitTerminalList(chat);
    }
  },

  emitTerminalList(chat) {
    this.emit({ event: 'chat.terminals', chatId: chat.id, terminals: chat.terminalList() });
  }

  /**
   * Adopt a Codex thread: attach the kernel to it and load the transcript the
   * kernel already holds. This is what makes "打开历史" and "新建对话" the same
   * path — only the reference differs (existing id vs none).
   */,
};
