/**
 * Approval broker — pure logic, no engine and no model involved.
 *
 * The rules being pinned down are the ones that keep a phone in the loop safe:
 * never hang, never guess silently, always leave a trace, and never let a stale
 * answer decide a different question.
 *
 *   node tools/approvals-test.js
 */
import { ApprovalBroker, OPTIONS, describe, isKnownOption } from '../src/approvals.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// --- with a client attached: the phone decides ------------------------------
{
  const broker = new ApprovalBroker({ timeoutMs: 5000 });
  const frames = [];
  broker.attach((payload) => frames.push(payload));

  const promise = broker.request({ chatId: 'c1', engine: 'opencode', title: '内核请求权限', detail: 'bash: npm test' });
  await wait(10);
  const requested = frames.find((f) => f.event === 'chat.approval' && !f.state);
  check('the phone receives a request frame', Boolean(requested), JSON.stringify(requested?.options?.map((o) => o.id) ?? []));
  check('all three options are offered',
    requested?.options?.map((o) => o.id).join(',') === 'allow_once,allow_always,deny',
    requested?.options?.map((o) => o.id).join(','));
  check('the request carries what will actually run', String(requested?.detail).includes('npm test'), requested?.detail);
  check('the request is listed as pending', broker.pending().length === 1);

  const resolved = broker.resolve({ requestId: requested.requestId, optionId: OPTIONS.ALLOW_ONCE });
  check('the phone answer is accepted', resolved.ok === true, resolved.message ?? '');
  check('the engine receives the phone answer', (await promise) === OPTIONS.ALLOW_ONCE);
  check('a settled request is no longer pending', broker.pending().length === 0);
  const settled = frames.find((f) => f.event === 'chat.approval' && f.state === 'resolved');
  check('the client is told it settled', settled?.optionId === OPTIONS.ALLOW_ONCE, settled?.by);
  check('the decision is recorded with who made it', broker.history.at(-1)?.by === 'phone');
}

// --- a stale or unknown answer must not decide anything ---------------------
{
  const broker = new ApprovalBroker({ timeoutMs: 5000 });
  broker.attach(() => {});
  const promise = broker.request({ chatId: 'c1', engine: 'codex', title: 't' });
  const stale = broker.resolve({ requestId: 'a-does-not-exist', optionId: OPTIONS.ALLOW_ONCE });
  check('an unknown request id is refused', stale.ok === false && stale.code === 'no_request', stale.message);

  const bad = broker.pending()[0].requestId;
  const wrong = broker.resolve({ requestId: bad, optionId: 'engine-option-7' });
  check('an option we never offered is refused', wrong.ok === false && wrong.code === 'bad_option', wrong.message);

  broker.resolve({ requestId: bad, optionId: OPTIONS.DENY });
  check('the first real answer wins', (await promise) === OPTIONS.DENY);
  const again = broker.resolve({ requestId: bad, optionId: OPTIONS.ALLOW_ONCE });
  check('a second answer to the same request is refused', again.ok === false && again.code === 'no_request');
}

// --- nobody to ask: settle now, do not wait for a client that may not return -
{
  const broker = new ApprovalBroker({ timeoutMs: 5000 });
  const verdict = await broker.request({ chatId: 'c1', engine: 'opencode', title: 't', fallback: OPTIONS.DENY });
  check('an offline phone settles immediately on the fallback', verdict === OPTIONS.DENY, verdict);
  check('and the record says why', broker.history.at(-1)?.by === 'offline', broker.history.at(-1)?.by);
}

// --- a phone that never answers must not hang a turn ------------------------
{
  const broker = new ApprovalBroker({ timeoutMs: 60 });
  broker.attach(() => {});
  const started = Date.now();
  const verdict = await broker.request({ chatId: 'c1', engine: 'opencode', title: 't', fallback: OPTIONS.ALLOW_ALWAYS });
  check('a timeout settles on the fallback', verdict === OPTIONS.ALLOW_ALWAYS, verdict);
  check('and does not settle earlier than the deadline', Date.now() - started >= 55, `${Date.now() - started}ms`);
  check('the record says it was a timeout', broker.history.at(-1)?.by === 'timeout');
}

// --- per-chat settling and shutdown ----------------------------------------
{
  const broker = new ApprovalBroker({ timeoutMs: 5000 });
  broker.attach(() => {});
  const a = broker.request({ chatId: 'c1', engine: 'opencode', title: 'a' });
  const b = broker.request({ chatId: 'c2', engine: 'opencode', title: 'b' });
  const closed = broker.cancelForChat('c1');
  check('closing a chat settles only its own requests', closed === 1, `${closed}`);
  check('the closed chat is denied', (await a) === OPTIONS.DENY);
  check('the other chat is untouched', broker.pending().length === 1);
  broker.disposeAll();
  check('shutdown settles what is left', (await b) === OPTIONS.DENY);
  check('nothing is pending afterwards', broker.pending().length === 0);
}

// --- a restricted menu ------------------------------------------------------
{
  const broker = new ApprovalBroker({ timeoutMs: 5000 });
  broker.attach(() => {});
  const promise = broker.request({ chatId: 'c1', engine: 'opencode', title: 't', ids: [OPTIONS.ALLOW_ONCE, OPTIONS.DENY], fallback: OPTIONS.ALLOW_ALWAYS });
  const req = broker.pending()[0];
  check('only the offered options are listed', req.options.length === 2, req.options.map((o) => o.id).join(','));
  check('a fallback that was not offered is not claimed', req.fallback === OPTIONS.DENY, req.fallback);
  broker.resolve({ requestId: req.requestId, optionId: OPTIONS.DENY });
  await promise;
}

// --- vocabulary -------------------------------------------------------------
check('only our three ids are known', isKnownOption('allow_once') && !isKnownOption('reject_once'));
check('a denial reads as a denial', describe({ title: 'x', detail: 'y' }, OPTIONS.DENY, 'phone').includes('已拒绝'));
check('a timeout says why it happened', describe({ title: 'x' }, OPTIONS.DENY, 'timeout').includes('没人回答'));
check('an offline default says why it happened', describe({ title: 'x' }, OPTIONS.DENY, 'offline').includes('没连上'));

const failures = results.filter((r) => !r.passed).length;
console.log(`\nApprovals: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);