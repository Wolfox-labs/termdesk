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
   * While a client is attached the entry is sent immediately: the phone knows whether it is
   * looking at that conversation, and it is the side that should decide whether to interrupt
   * somebody who is already reading. This side only knows that something happened.
   */
  push(entry) {
    const at = this.now();
    const record = { id: `n-${this.nextId++}`, at, ...entry };
    if (this.send) {
      try {
        this.send(record);
        this.log?.(`[termdesk] notify ${record.id} ${record.kind}${chatOf(record)} → 客户端在线，直接送出`);
        return record;
      } catch {
        // A socket that died between the check and the send: fall through and hold it,
        // because the news is still true.
      }
    }
    record.whileAway = true;
    this.entries.push(record);
    this.prune();
    this.log?.(
      `[termdesk] notify ${record.id} ${record.kind}${chatOf(record)} → 没有客户端，暂存（待发 ${this.entries.length} 条）`,
    );
    return record;
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
   * Hand every held entry to `send`, oldest first, and forget them.
   *
   * Forgotten on delivery rather than on being read: the phone has the frame at that point,
   * and a phone in the background can post its own notification from it. Entries that fail
   * to send stay held, so a socket that dies mid-flush does not swallow the rest.
   */
  deliver(send) {
    this.prune();
    const waiting = [...this.entries];
    const failed = [];
    let sent = 0;
    for (const entry of waiting) {
      try {
        send(entry);
        sent += 1;
      } catch {
        failed.push(entry);
      }
    }
    this.entries = failed;
    if (waiting.length > 0) {
      this.log?.(
        `[termdesk] notify 补发 ${sent}/${waiting.length} 条`
        + (failed.length ? `（${failed.length} 条没发出去，仍留着）` : ''),
      );
    }
    return sent;
  }
}

/** ` chat=c-3` for the log line, or nothing when the entry is not about a conversation. */
function chatOf(record) {
  return record.chatId ? ` chat=${record.chatId}` : '';
}
