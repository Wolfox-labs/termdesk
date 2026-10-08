/**
 * Echoing the request id back, so an answer cannot be mistaken for another answer.
 *
 * The defect this closes is the one a device test would NOT have caught: everything
 * runs, and only the CONTENTS are wrong (A's listing under B's name) when two
 * requests are in flight on a slow link.
 *
 * `withCallId` is the whole mechanism, and it is deliberately small: the agent does
 * not interpret the id, only returns it.
 *
 * Free: no socket is opened.
 *
 *   node tools/call-id-test.js
 */
import { callIdOf, withCallId } from '../src/callid.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// ---- reading the id off the request -----------------------------------------

check('the id is read off the request', callIdOf({ callId: 'fs.list-1' }) === 'fs.list-1');
check('a request without one yields null', callIdOf({ path: '/tmp' }) === null);
check('a blank id counts as absent', callIdOf({ callId: '' }) === null, 'echoing "" would match nothing');
check('a non-string id is ignored rather than stringified',
  callIdOf({ callId: 42 }) === null && callIdOf({ callId: null }) === null);
check('a missing frame does not throw', callIdOf(undefined) === null && callIdOf(null) === null);

// ---- putting it on the answer -----------------------------------------------

{
  const listing = { path: '/home/me', entries: [{ name: 'a.txt' }] };
  const out = withCallId(listing, { callId: 'fs.list-7', path: '/home/me' });
  check('the answer carries the id', out.callId === 'fs.list-7', out.callId);
  check('and the answer itself is untouched',
    out.path === '/home/me' && out.entries.length === 1, JSON.stringify(out));
  check('the original payload is not mutated', listing.callId === undefined, JSON.stringify(listing));
}

{
  // The important compatibility case: an older client sends no id, and must see
  // exactly the frame it saw before - no new field, no new version required.
  const listing = { path: '/home/me', entries: [] };
  const out = withCallId(listing, { path: '/home/me' });
  check('a client that sends no id gets the frame unchanged, not a callId of undefined',
    out === listing, 'identity, so byte-for-byte the same');
}

{
  const out = withCallId({ path: '/x' }, { callId: '  ' });
  check('a whitespace id is absent, not echoed', out.callId === undefined, JSON.stringify(out));
}

{
  // The error path matters most: a failure the phone cannot attribute is a failure
  // it cannot report, and it would sit waiting for an answer that already came.
  const out = withCallId({ code: 'enoent', message: 'no such directory' }, { callId: 'fs.list-9' });
  check('an error is attributed too', out.callId === 'fs.list-9' && out.code === 'enoent', JSON.stringify(out));
}

{
  const out = withCallId(null, { callId: 'fs.read-1' });
  check('a reply that carries no payload still gets the id', out.callId === 'fs.read-1', JSON.stringify(out));
}

{
  // A reply that already knows its own id is not overwritten by the echo.
  const out = withCallId({ callId: 'inner', path: '/x' }, { callId: 'outer' });
  check('the answer\'s own id wins over the echo', out.callId === 'inner', out.callId);
}

// ---- the property that actually matters -------------------------------------

{
  // Two listings answered out of order: each answer still names its own question.
  const a = withCallId({ path: '/A' }, { callId: 'q1' });
  const b = withCallId({ path: '/B' }, { callId: 'q2' });
  const arrived = [b, a]; // B answered first
  const apply = (answer, wanted) => (answer.callId === wanted ? answer.path : null);
  check('an answer arriving late for the abandoned question is identifiable as stale',
    apply(arrived[0], 'q2') === '/B' && apply(arrived[1], 'q2') === null,
    'this is what stops /A being shown under /B');
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
