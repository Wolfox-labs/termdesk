/**
 * Version negotiation, pinned without a socket.
 *
 * The two rules worth having tests for are the ones that decide whether this
 * check is a fix or a regression:
 *
 *   - a client that declares nothing must be accepted (the field is new; every
 *     installed app is such a client);
 *   - a client NEWER than the agent must be accepted too, because the agent is
 *     the side nobody updates.
 *
 *   node tools/protocol-negotiation-test.js
 */
import { PROTOCOL_VERSION } from '../src/protocol.js';
import {
  MIN_SUPPORTED_PROTOCOL,
  parseVersion,
  judgeClientVersion,
  judgeAgentVersion,
} from '../src/version.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// ---- parsing ----------------------------------------------------------------

check('a number is a version', parseVersion(1) === 1);
check('a numeric string is a version', parseVersion('2') === 2);
check('a float is truncated, not rejected', parseVersion(2.9) === 2);
check('zero is not a version', parseVersion(0) === null);
check('a negative is not a version', parseVersion(-3) === null);
check('garbage is not a version', parseVersion('abc') === null);
check('an absent version is not a version', parseVersion(undefined) === null);
check('null is not a version', parseVersion(null) === null);
check('an empty string is not a version', parseVersion('') === null);

// ---- the agent judging a client ---------------------------------------------

{
  const same = judgeClientVersion(PROTOCOL_VERSION);
  check('a client on our version is accepted', same.ok === true);
  check('and needs no upgrade', same.upgrade === 'none', same.upgrade);
  check('and reports both versions', same.clientV === PROTOCOL_VERSION && same.serverV === PROTOCOL_VERSION);
}

{
  // The compatibility promise: every app installed before this field existed.
  const silent = judgeClientVersion(undefined);
  check('a client that declares NO version is treated as v1 and accepted', silent.ok === true, JSON.stringify(silent));
  check('so an older installed app is not locked out', silent.clientV === 1);
}

{
  // The floor is injected because `MIN_SUPPORTED_PROTOCOL - 1` is 0, and 0 is not
  // a version any client can declare (the parser reads it as "declared nothing",
  // which is the compatible case). Testing the refusal path with a hypothetical
  // floor also means this stays true after a real bump.
  const older = judgeClientVersion(1, 2);
  check('a client below the floor is refused', older.ok === false, JSON.stringify(older));
  check('and is told to upgrade the APP, not the agent', older.upgrade === 'app', older.upgrade);
  check('and the reason names both versions', /v1/.test(older.reason ?? '') && /v2/.test(older.reason ?? ''), older.reason);
  check('a client exactly on the floor is accepted', judgeClientVersion(2, 2).ok === true);
}

{
  // The agent is the side nobody updates; refusing a newer app would break every
  // future release against it.
  const newer = judgeClientVersion(PROTOCOL_VERSION + 1);
  check('a client NEWER than the agent is accepted', newer.ok === true, JSON.stringify(newer));
  check('but the answer says the agent is the one behind', newer.upgrade === 'agent', newer.upgrade);
}

check('the floor is never above what we speak', MIN_SUPPORTED_PROTOCOL <= PROTOCOL_VERSION,
  `min=${MIN_SUPPORTED_PROTOCOL} server=${PROTOCOL_VERSION}`);

// ---- the phone judging the agent --------------------------------------------

{
  const same = judgeAgentVersion(PROTOCOL_VERSION, MIN_SUPPORTED_PROTOCOL);
  check('an agent on our version is exact', same.ok === true && same.upgrade === 'none', JSON.stringify(same));
}

{
  // An agent from before this handshake existed.
  const silent = judgeAgentVersion(undefined, undefined);
  check('an agent that declares nothing is accepted as old, not refused', silent.ok === true);
  check('and the phone knows the agent is behind', silent.upgrade === 'agent', silent.upgrade);
}

{
  const demanding = judgeAgentVersion(PROTOCOL_VERSION, PROTOCOL_VERSION + 1);
  check('an agent that requires a newer app is refused', demanding.ok === false);
  check('and the phone is told to upgrade the APP', demanding.upgrade === 'app', demanding.upgrade);
}

{
  const newer = judgeAgentVersion(PROTOCOL_VERSION + 1, undefined);
  check('an agent newer than the app still works', newer.ok === true);
  check('and the phone is the side to update', newer.upgrade === 'app', newer.upgrade);
}

{
  const olderAgent = judgeAgentVersion(PROTOCOL_VERSION - 1, undefined);
  check('an agent older than the app works but is the side to update',
    olderAgent.ok === true && olderAgent.upgrade === 'agent', JSON.stringify(olderAgent));
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
