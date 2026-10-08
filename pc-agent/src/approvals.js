/**
 * Approvals — one place where an engine asks the phone for permission.
 *
 * Two engines needed this and each had its own half-answer:
 *
 *   Codex  sends a server->client REQUEST before a command or a patch. TermDesk
 *          answered every one of them with an explicit refusal, so the turn
 *          failed visibly instead of hanging.
 *   ACP    sends `session/request_permission` with the options the kernel
 *          accepts. TermDesk answered it from a fixed policy (allow, by default)
 *          and wrote a line into the transcript afterwards.
 *
 * Neither is what the product should do: the person holding the phone is the one
 * who decides what runs on their machine. This broker is the single path for
 * both — the engine's request becomes a question on the phone, and the engine's
 * own vocabulary is used for the answer:
 *
 *   allow_once    run it this time
 *   allow_always  run it for the rest of this conversation
 *   deny          do not run it
 *
 * Three rules make it safe to have a phone in the loop:
 *
 *   1. Never hang. Every request has a deadline; when it passes, the request
 *      settles on `fallback` and records why. A phone that goes to sleep in a
 *      tunnel must not freeze a turn forever.
 *   2. Never guess silently. If no phone is attached at all, the request settles
 *      immediately on the same fallback instead of waiting for a client that may
 *      never come back.
 *   3. Always leave a trace. Every decision (whoever made it, including "timeout"
 *      and "offline") is emitted as an event so the transcript says what was
 *      allowed on this machine.
 */
import { EventEmitter } from 'node:events';
import crypto from 'node:crypto';

/** The vocabulary every engine's answer is translated into. */
export const OPTIONS = {
  ALLOW_ONCE: 'allow_once',
  ALLOW_ALWAYS: 'allow_always',
  DENY: 'deny',
};

const MAX_HISTORY = 50;

export class ApprovalBroker extends EventEmitter {
  constructor({ timeoutMs = 5 * 60_000, log = console.log } = {}) {
    super();
    this.timeoutMs = timeoutMs;
    this.log = log;
    /** requestId -> pending record */
    this.pendingRequests = new Map();
    /** Settled decisions, newest last, for the transcript and for diagnostics. */
    this.history = [];
    /** Set by the socket layer; the broker never touches a socket itself. */
    this.onEvent = null;
  }

  /** Route approval events to the connected client. */
  attach(onEvent) {
    this.onEvent = onEvent;
  }

  detach() {
    this.onEvent = null;
  }

  get hasClient() {
    return this.onEvent !== null;
  }

  emitEvent(payload) {
    try {
      this.onEvent?.(payload);
    } catch {
      // A dead socket must never take a pending turn down with it.
    }
  }

  /** Outstanding requests, for a client that just connected. */
  pending() {
    return [...this.pendingRequests.values()].map((rec) => rec.public);
  }

  /**
   * Ask. Resolves with one of [OPTIONS].
   *
   * @param {object} options
   * @param {string|null} options.chatId    conversation the request belongs to
   * @param {string} options.engine         'codex' | 'acp kernel id'
   * @param {string} options.title          one line, shown as the question
   * @param {string} [options.detail]       what exactly is being asked for
   * @param {string} [options.kind]         command | file | tool
   * @param {string[]} [options.ids]        option ids to offer; defaults to all three
   * @param {string} [options.fallback]     what to do if nobody answers
   */
  request({ chatId, engine, title, detail = '', kind = 'tool', ids = null, fallback = OPTIONS.DENY } = {}) {
    const requestId = `a-${crypto.randomUUID()}`;
    const offered = (ids ?? [OPTIONS.ALLOW_ONCE, OPTIONS.ALLOW_ALWAYS, OPTIONS.DENY])
      .filter((id) => typeof id === 'string' && id.length > 0);
    const publicRequest = {
      requestId,
      chatId: chatId ?? null,
      engine,
      title,
      detail: String(detail).slice(0, 2000),
      kind,
      options: offered.map((id) => ({ id, label: labelFor(id), style: styleFor(id) })),
      // An unanswered question must never settle on an ALLOW that the engine
      // did not even offer. `deny` is always a legal outcome for us (engines that
      // have no "no" option are told "cancelled"), so it is the safe default when
      // the requested fallback cannot be expressed.
      fallback: offered.includes(fallback) ? fallback : OPTIONS.DENY,
      expiresAt: Date.now() + this.timeoutMs,
      timeoutMs: this.timeoutMs,
    };

    // Rule 2: with nobody to ask, answer now rather than waiting for a client
    // that may never come back.
    if (!this.hasClient) {
      return Promise.resolve(this.settle(publicRequest, publicRequest.fallback, 'offline'));
    }

    return new Promise((resolve) => {
      const record = {
        public: publicRequest,
        resolve,
        timer: setTimeout(() => {
          // Rule 1: never hang.
          resolve(this.settle(publicRequest, publicRequest.fallback, 'timeout'));
        }, this.timeoutMs),
      };
      this.pendingRequests.set(requestId, record);
      this.emitEvent({ event: 'chat.approval', ...publicRequest });
      // Somebody has to answer this and the kernel is blocked until they do, which makes it
      // the one event worth waking a phone for. The listener decides how; this only reports
      // that it happened.
      this.emit('requested', publicRequest);
    });
  }

  /**
   * The phone's answer. Unknown or stale ids are refused rather than trusted:
   * an approval that is not currently pending must not be able to settle a
   * different question.
   */
  resolve({ requestId, optionId, by = 'phone' } = {}) {
    const record = this.pendingRequests.get(requestId);
    if (!record) return { ok: false, code: 'no_request', message: '这条审批已经结束或不存在' };
    const offered = record.public.options.map((o) => o.id);
    if (!offered.includes(optionId)) {
      return { ok: false, code: 'bad_option', message: `该请求不接受这个选项：${optionId}` };
    }
    this.pendingRequests.delete(requestId);
    clearTimeout(record.timer);
    record.resolve(this.settle(record.public, optionId, by));
    return { ok: true, optionId };
  }

  /** Settle one request: record it, tell the client, hand the answer back. */
  settle(publicRequest, optionId, by) {
    const decision = {
      requestId: publicRequest.requestId,
      chatId: publicRequest.chatId,
      engine: publicRequest.engine,
      title: publicRequest.title,
      optionId,
      by,
      at: Date.now(),
    };
    this.history.push(decision);
    if (this.history.length > MAX_HISTORY) this.history.shift();
    // A settled request still goes out, so a phone showing the dialog can close
    // it even when the decision came from a timeout.
    this.emitEvent({ event: 'chat.approval', ...publicRequest, state: 'resolved', optionId, by });
    // The transcript line is written by whoever owns the conversation (through
    // the chat pipeline), so it lands in the right conversation, in order, and
    // with the same event shape as everything else the phone renders.
    this.emit('settled', {
      request: publicRequest,
      optionId,
      by,
      note: describe(publicRequest, optionId, by),
    });
    return optionId;
  }

  /** Settle everything a conversation was waiting on (closed / stopped chat). */
  cancelForChat(chatId, optionId = OPTIONS.DENY, by = 'chat-closed') {
    let count = 0;
    for (const [requestId, record] of [...this.pendingRequests]) {
      if (record.public.chatId !== chatId) continue;
      this.pendingRequests.delete(requestId);
      clearTimeout(record.timer);
      record.resolve(this.settle(record.public, optionId, by));
      count += 1;
    }
    return count;
  }

  /** Settle everything (agent shutting down). */
  disposeAll() {
    for (const [requestId, record] of [...this.pendingRequests]) {
      this.pendingRequests.delete(requestId);
      clearTimeout(record.timer);
      record.resolve(this.settle(record.public, record.public.fallback, 'shutdown'));
    }
    this.onEvent = null;
  }
}

/** User-facing labels, in the same voice as the rest of the phone UI. */
export function labelFor(optionId) {
  switch (optionId) {
    case OPTIONS.ALLOW_ONCE: return '允许一次';
    case OPTIONS.ALLOW_ALWAYS: return '这个会话内总是允许';
    case OPTIONS.DENY: return '拒绝';
    default: return optionId;
  }
}

export function styleFor(optionId) {
  if (optionId === OPTIONS.DENY) return 'danger';
  if (optionId === OPTIONS.ALLOW_ALWAYS) return 'neutral';
  return 'primary';
}

/** The line that goes into the conversation so the decision is auditable. */
export function describe(request, optionId, by) {
  const what = request.detail ? `${request.title}：${request.detail}` : request.title;
  const why = by === 'timeout' ? '（没人回答，按默认处理）'
    : by === 'offline' ? '（手机没连上，按默认处理）'
      : by === 'chat-closed' ? '（会话已结束）'
        : by === 'policy' ? '（按当前策略）'
          : '';
  const verdict = optionId === OPTIONS.DENY ? '已拒绝' : labelFor(optionId);
  return `${what} → ${verdict}${why}`;
}

/** True for our own option ids, so a phone cannot smuggle in an engine's raw id. */
export function isKnownOption(optionId) {
  return optionId === OPTIONS.ALLOW_ONCE || optionId === OPTIONS.ALLOW_ALWAYS || optionId === OPTIONS.DENY;
}