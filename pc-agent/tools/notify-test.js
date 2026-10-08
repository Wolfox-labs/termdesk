/**
 * Notifications are decided here, held while nobody is listening, and delivered once.
 *
 * The rules this pins, and why each one is a rule:
 *
 *   - while a client is attached, an entry goes straight out, because the phone is the side
 *     that knows whether the person is already reading that conversation;
 *   - while nobody is attached, it is HELD and marked `whileAway` — the phone is usually
 *     away when the work finishes, which is the entire point of a notification;
 *   - held entries are delivered oldest first and then forgotten, so a reconnect cannot
 *     produce the same notification twice;
 *   - an entry that fails to send stays held, because a socket dying mid-flush must not
 *     swallow the rest;
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

// --- attached: straight out ------------------------------------------------
const sent = [];
notifier.attach((payload) => sent.push(payload));
const live = notifier.push({ kind: 'approval', chatId: 'c1', title: 'need you' });
check('with a client attached an entry goes out immediately', sent.length === 1, String(sent.length));
check('and it is not kept', notifier.size() === 1, `held=${notifier.size()}`);
check('an entry sent live is not marked whileAway', live.whileAway === undefined,
  JSON.stringify(live.whileAway));

notifier.detach();
notifier.push({ kind: 'turn_done', chatId: 'c2', title: 't2' });
check('detaching means news is held again', notifier.size() === 2, String(notifier.size()));

// --- delivery --------------------------------------------------------------
const flushed = [];
const count = notifier.deliver((payload) => flushed.push(payload));
check('everything held is delivered', count === 2, String(count));
check('oldest first', flushed[0]?.chatId === 'c1' && flushed[1]?.chatId === 'c2',
  flushed.map((f) => f.chatId).join(','));
check('all of it is marked as having waited', flushed.every((f) => f.whileAway === true));
check('and nothing is kept afterwards', notifier.size() === 0, String(notifier.size()));
check('a second delivery sends nothing (no notification twice)',
  notifier.deliver(() => { throw new Error('must not be called'); }) === 0);

// --- a send that fails keeps the entry -------------------------------------
notifier.push({ kind: 'turn_done', chatId: 'c3', title: 't3' });
let attempts = 0;
const delivered = notifier.deliver(() => {
  attempts += 1;
  throw new Error('socket died');
});
check('a failed delivery reports nothing sent', delivered === 0, String(delivered));
check('and the entry is still held, not swallowed', notifier.size() === 1, String(notifier.size()));
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
