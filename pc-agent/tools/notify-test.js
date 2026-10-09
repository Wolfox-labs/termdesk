/**
 * Notifications are decided here, held until the phone says it has them, and re-sent after that.
 *
 * The rules this pins, and why each one is a rule:
 *
 *   - while a client is attached, an entry goes straight out, because the phone is the side
 *     that knows whether the person is already reading that conversation;
 *   - while nobody is attached, it is HELD and marked `whileAway` — the phone is usually
 *     away when the work finishes, which is the entire point of a notification;
 *   - **and it stays held either way until the phone acks it.** Writing to a socket is not
 *     delivery: measured on a real phone, Android suspended the app, the frame sat in the
 *     connection's buffer, and when the connection finally died the news died with it. The
 *     old rule ("forget it once written") lost notifications in exactly the case they exist
 *     for, and lost them silently;
 *   - an entry that fails to send stays held, because a socket dying mid-flush must not
 *     swallow the rest;
 *   - a repeat send is marked `whileAway` only once: it is the same news, and marking it
 *     again would date it wrongly on the second attempt;
 *   - and there is a cap and a lifetime, because an agent left running overnight would
 *     otherwise replay a hundred stale lines at the next connection.
 *
 * The clock is injected, so the lifetime rule is tested rather than waited for.
 *
 *   node tools/notify-test.js
 */
const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const { Notifier } = await import('../src/notify.js');

let clock = 1_000_000;
const now = () => clock;
const notifier = new Notifier({ limit: 3, ttlMs: 60_000, now });

// --- nobody attached: held -------------------------------------------------
const held = notifier.push({ kind: 'turn_done', chatId: 'c1', title: 't', text: 'done' });
check('with nobody attached an entry is held', notifier.size() === 1, String(notifier.size()));
check('and it is marked as having happened while the phone was away',
  held.whileAway === true, JSON.stringify(held.whileAway));
check('it is stamped with a time and an id', Boolean(held.id) && held.at === clock,
  `${held.id}@${held.at}`);

// --- attached: straight out, and STILL held until acked --------------------
const sent = [];
notifier.attach((payload) => sent.push(payload));
const live = notifier.push({ kind: 'approval', chatId: 'c1', title: 'need you' });
check('with a client attached an entry goes out immediately', sent.length === 1, String(sent.length));
check('an entry sent live is not marked whileAway', live.whileAway === undefined,
  JSON.stringify(live.whileAway));
check('but it is still kept until the phone confirms it', notifier.size() === 2,
  `held=${notifier.size()}`);

notifier.detach();
notifier.push({ kind: 'turn_done', chatId: 'c2', title: 't2' });
check('detaching means news is held again', notifier.size() === 3, String(notifier.size()));

// --- delivery --------------------------------------------------------------
const flushed = [];
const count = notifier.deliver((payload) => flushed.push(payload));
check('everything unconfirmed is delivered', count === 3, String(count));
check('oldest first', flushed.map((f) => f.chatId).join(',') === 'c1,c1,c2',
  flushed.map((f) => f.chatId).join(','));
// The one that had already gone out live is re-sent as "while you were away" too, because
// nothing has confirmed it: as far as this side knows the phone has never shown it.
check('everything unconfirmed is sent as "while you were away"',
  flushed.map((f) => f.whileAway).join(',') === 'true,true,true',
  flushed.map((f) => f.whileAway).join(','));
check('and all of it is still awaiting confirmation', notifier.size() === 3, String(notifier.size()));

// --- the ack is what forgets ----------------------------------------------
const firstId = flushed[0].id;
const forgotten = notifier.ack([firstId]);
check('confirming one entry forgets exactly that one', forgotten === 1, String(forgotten));
check('and the rest are still waiting', notifier.size() === 2, String(notifier.size()));
const rest = notifier.ack(notifier.list().map((e) => e.id));
check('confirming the rest empties it', rest === 2, String(rest));
check('a second delivery after confirmation sends nothing',
  notifier.deliver(() => { throw new Error('must not be called'); }) === 0);
check('an unknown id changes nothing', notifier.ack(['n-999']) === 0);
check('an ack with nothing in it is not an error', notifier.ack([]) === 0 && notifier.ack(undefined) === 0);

// A repeat send keeps the ORIGINAL "while you were away" marking: it is one piece of news,
// and re-dating it would move it forward every time the phone reconnects.
const repeat = notifier.push({ kind: 'turn_done', chatId: 'c4', title: 't4' });
check('news that never went out is marked as having waited', repeat.whileAway === true);
notifier.deliver(() => {});
const firstSendAt = repeat.sentAt;
clock += 5_000;
notifier.deliver(() => {});
check('a re-send moves the sent time forward', repeat.sentAt > firstSendAt,
  `${firstSendAt} -> ${repeat.sentAt}`);
check('and it is still the same "while you were away" news', repeat.whileAway === true);

// --- a send that fails keeps the entry -------------------------------------
const failing = new Notifier({ limit: 3, ttlMs: 60_000, now });
failing.push({ kind: 'turn_done', chatId: 'c3', title: 't3' });
let attempts = 0;
const delivered = failing.deliver(() => {
  attempts += 1;
  throw new Error('socket died');
});
check('a failed delivery reports nothing sent', delivered === 0, String(delivered));
check('and the entry is still held, not swallowed', failing.size() === 1, String(failing.size()));
check('the rest of a flush still gets its turn', attempts === 1, String(attempts));

// --- the cap ---------------------------------------------------------------
const small = new Notifier({ limit: 3, ttlMs: 60_000, now });
for (let i = 1; i <= 5; i += 1) small.push({ kind: 'turn_done', chatId: `c${i}` });
const heldIds = small.list().map((e) => e.chatId);
check('the cap keeps the newest and drops the oldest', heldIds.join(',') === 'c3,c4,c5', heldIds.join(','));

// --- the lifetime ----------------------------------------------------------
const before = small.size();
clock += 61_000;
check('entries older than the lifetime are forgotten', small.size() === 0,
  `was ${before}, now ${small.size()}`);
check('and a stale entry is not delivered', small.deliver(() => { throw new Error('no'); }) === 0);

const failed = results.filter((r) => !r.passed);
console.log(`\nNotifier: ${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length === 0 ? 0 : 1);
