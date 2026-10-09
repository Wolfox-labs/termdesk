/**
 * What the phone should be told about while it is not looking.
 *
 * The decision to notify is made HERE, on the PC, and not on the phone. The phone is a
 * display shell; this is the side that knows when a turn actually ended, when a kernel is
 * blocked waiting for an answer, and when a turn failed. A phone inferring it from the
 * event stream would have to notice an absence — and it may have missed the middle of the
 * stream, which is exactly the situation a notification is for.
 *
 * Held, not dropped, when nobody is attached: the phone is usually away when the work
 * finishes. Held entries are delivered on the next authentication and marked `whileAway`,
 * so the phone can say "while you were away" instead of pretending it just happened.
 *
 * Nothing here outlives the agent process, and that limit is deliberate: a notification
 * about a turn this agent no longer remembers would open a conversation that is not there.
 */
export class Notifier {
  constructor({ limit = 20, ttlMs = 24 * 60 * 60 * 1000, now = () => Date.now(), log = null } = {}) {
    this.limit = limit;
    this.ttlMs = ttlMs;
    this.now = now;
    /**
     * One line per decision, and nothing by default.
     *
     * "The phone was never told" is the complaint this whole file exists to prevent, and
     * from the outside the three ways it can happen — never judged worth telling, held
     * because nobody was attached, or handed to a socket that did not take it — look
     * identical. The caller passes a logger in production; tests stay silent.
     */
    this.log = log;
    this.entries = [];
    this.nextId = 1;
    /** Set while a client is attached; then entries go straight out. */
    this.send = null;
  }

  /** Route to the connected client. While routed, nothing is held. */
  attach(send) {
    this.send = send;
  }

  detach() {
    this.send = null;
  }

  /**
   * Remember one thing worth telling the phone about, and drop what is no longer worth it.
   *
   * Every entry is kept until the phone says it has it (see `ack`), whether or not a client
   * is attached right now. That is not belt-and-braces: writing to a socket is not delivery.
   * Measured on a real phone — backgrounded, the frame went into a socket that stayed open and
   * the process did nothing with it, and when the connection finally died the buffered bytes
   * died with it. The PC had already forgotten the news, so it was lost in silence.
   *
   * While a client is attached the entry goes out immediately as well: the phone knows whether
   * it is looking at that conversation, and it is the side that should decide whether to
   * interrupt somebody who is already reading. This side only knows that something happened.
   */
  push(entry) {
    const at = this.now();
    const record = { id: `n-${this.nextId++}`, at, ...entry };
    this.entries.push(record);
    this.prune();
    if (this.send) {
      try {
        this.send(record);
        record.sentAt = this.now();
        this.log?.(
          `[termdesk] notify ${record.id} ${record.kind}${chatOf(record)} → 客户端在线，送出（等它确认）`,
        );
        return record;
      } catch {
        // A socket that died between the check and the send: the entry is already held,
        // because the news is still true.
      }
    }
    record.whileAway = true;
    this.log?.(
      `[termdesk] notify ${record.id} ${record.kind}${chatOf(record)} → 没有客户端，暂存（待发 ${this.entries.length} 条）`,
    );
    return record;
  }

  /**
   * The phone says it has these. Only now is the news forgotten.
   *
   * A phone that received a notification while somebody was reading that very conversation
   * still acks it: "I have it, I chose not to interrupt" is a decision, not a loss, and
   * re-sending it on every reconnect would turn the decision into an argument.
   */
  ack(ids) {
    const wanted = new Set((Array.isArray(ids) ? ids : [ids]).filter((id) => typeof id === 'string'));
    if (wanted.size === 0) return 0;
    const before = this.entries.length;
    this.entries = this.entries.filter((e) => !wanted.has(e.id));
    const dropped = before - this.entries.length;
    if (dropped > 0) {
      this.log?.(`[termdesk] notify 手机确认收到 ${dropped} 条（还剩 ${this.entries.length} 条没确认）`);
    }
    return dropped;
  }

  /**
   * Forget entries nobody can act on any more.
   *
   * Two rules, and the cap is not decoration: an agent left running overnight would
   * otherwise replay a hundred "the turn ended" lines at the next connection, which is how
   * a notification list becomes something people swipe away without reading.
   */
  prune() {
    const cutoff = this.now() - this.ttlMs;
    this.entries = this.entries.filter((e) => e.at >= cutoff);
    if (this.entries.length > this.limit) {
      this.entries = this.entries.slice(this.entries.length - this.limit);
    }
  }

  /** How many are waiting to be delivered. */
  size() {
    this.prune();
    return this.entries.length;
  }

  /** The held entries, oldest first. */
  list() {
    this.prune();
    return [...this.entries];
  }

  /**
   * Hand every unconfirmed entry to `send`, oldest first. They stay until acked.
   *
   * The flush happens on every authentication, so a phone that comes back after being
   * suspended — or after the agent was restarted — is told everything it has not confirmed.
   * Sending the same entry twice is harmless by design: the phone's notification id is derived
   * from the conversation, so a repeat replaces the notification it already showed instead of
   * stacking a second copy of it.
   */
  deliver(send) {
    this.prune();
    const waiting = [...this.entries];
    const failed = [];
    let sent = 0;
    for (const entry of waiting) {
      try {
        // As far as this side knows, nobody has seen this yet — an entry is only forgotten
        // once the phone says it has it, so an unconfirmed one being sent again is news the
        // phone has never shown. "While you were away" is the honest sentence for it, and the
        // phone replaces its own notification rather than stacking a second copy.
        entry.whileAway = true;
        send(entry);
        entry.sentAt = this.now();
        sent += 1;
      } catch {
        failed.push(entry);
      }
    }
    if (waiting.length > 0) {
      this.log?.(
        `[termdesk] notify 补发 ${sent}/${waiting.length} 条（都在等手机确认）`
        + (failed.length ? `，其中 ${failed.length} 条没发出去` : ''),
      );
    }
    return sent;
  }
}

/** ` chat=c-3` for the log line, or nothing when the entry is not about a conversation. */
function chatOf(record) {
  return record.chatId ? ` chat=${record.chatId}` : '';
}
