/**
 * The kernel contract: how a kernel is DRIVEN, asked of the table instead of by name.
 *
 * The coupling this closes was measured, not assumed: `chat.js` decided
 * `engine === 'codex'` in eleven places, and `engine === 'dsh'` in one, so the
 * manager knew the names of individual kernels. Adding a kernel then meant reading
 * the manager to discover which method to add, and forgetting one produced a kernel
 * that worked everywhere except in `cancel`.
 *
 * This test does two things a "does it return true" test could not:
 *
 *   1. it pins each answer for every kernel in the REAL table, so a capability that
 *      silently changes is caught;
 *   2. it pins that the table-driven answer equals the name-based answer it
 *      replaces, for the kernels that exist - and for invented ones, which is where
 *      a name-based check has no answer at all.
 *
 * Free: nothing is spawned, nothing is probed.
 *
 *   node tools/kernel-contract-test.js
 */
import {
  TURN_DRIVERS,
  MODEL_ROUTES,
  transportOf,
  turnDriver,
  canResume,
  collectsLiveTerminals,
  ownsSessionStore,
  cancelStyle,
  modelRoute,
  kernelCapabilities,
  unknownKernelMessage,
} from '../src/kernels/contract.js';
import { listKernels } from '../src/kernels/registry.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** The table as the contract sees it: by id, with the entry's own fields. */
const lookup = (id) => {
  const k = listKernels().find((entry) => entry.id === id);
  return k ? { ...k, resume: k.resume, liveTerminals: k.liveTerminals } : null;
};

const ids = listKernels().map((k) => k.id);

// ---- the answers the real table gives ---------------------------------------

const EXPECTED = {
  codex: { driver: 'app-server', resume: true, live: true, cancel: 'ask-kernel' },
  dsh: { driver: 'sdk', resume: false, live: false, cancel: 'kill-process' },
  opencode: { driver: 'acp', resume: true, live: false, cancel: 'close-session' },
  mimo: { driver: 'acp', resume: true, live: false, cancel: 'close-session' },
  'command-code': { driver: 'acp', resume: true, live: false, cancel: 'close-session' },
  qoder: { driver: 'acp', resume: true, live: false, cancel: 'close-session' },
  antigravity: { driver: 'none', resume: false, live: false, cancel: 'none' },
  doubao: { driver: 'none', resume: false, live: false, cancel: 'none' },
};

for (const [id, want] of Object.entries(EXPECTED)) {
  if (!ids.includes(id)) {
    check(`the table still knows ${id}`, false, `ids=${ids.join(',')}`);
    continue;
  }
  const caps = kernelCapabilities(id, lookup);
  check(`${id}: driver=${want.driver}, resume=${want.resume}, liveTerminals=${want.live}, cancel=${want.cancel}`,
    caps.driver === want.driver && caps.resumable === want.resume
      && caps.liveTerminals === want.live && caps.cancel === want.cancel,
    JSON.stringify(caps));
}

check('every driver named is one the manager actually implements',
  [...new Set(ids.map((id) => turnDriver(id, lookup)))].every((d) => TURN_DRIVERS.includes(d)),
  [...new Set(ids.map((id) => turnDriver(id, lookup)))].join(','));

// ---- the same answer the name-based code gave -------------------------------
//
// This is the equivalence that makes the replacement safe: for the kernels that
// exist, the table says exactly what the hard-coded name checks said.
{
  const nameBasedDriver = (id) => {
    if (id === 'codex') return 'app-server';
    if (id === 'dsh') return 'sdk';
    return 'acp'; // isAcpKernel || isCliKernel
  };
  const diverged = ids
    .filter((id) => EXPECTED[id] && EXPECTED[id].driver !== 'none')
    .filter((id) => nameBasedDriver(id) !== turnDriver(id, lookup));
  check('the table-driven driver equals the name-based one for every real kernel',
    diverged.length === 0, diverged.join(','));
}

{
  // The old refusal, in the exact shape the manager wrote it: every kernel with a
  // driver could resume except dsh. Kernels with NO driver were never offered for
  // resume at all by the old code either - the registry did not list them as
  // selectable - so the reference has to include that, or it is asserting something
  // the old code never did.
  const nameBasedResume = (id) => EXPECTED[id]?.driver !== 'none' && id !== 'dsh';
  const diverged = ids.filter((id) => EXPECTED[id] && nameBasedResume(id) !== canResume(id, lookup));
  check('and resumability matches the old special case for dsh, over the kernels that had a driver',
    diverged.length === 0, diverged.join(','));
}

// ---- kernels that do not exist ----------------------------------------------
//
// The point of a contract: an id this build has never heard of gets a defined
// answer instead of falling through to whichever kernel was checked last.

check('an unknown kernel gets no driver', turnDriver('made-up-kernel', lookup) === 'none');
check('an unknown kernel cannot be resumed', canResume('made-up-kernel', lookup) === false);
check('an unknown kernel has no live terminals', collectsLiveTerminals('made-up-kernel', lookup) === false);
check('an unknown kernel is reported, not crashed',
  String(unknownKernelMessage('made-up-kernel', lookup)).includes('made-up-kernel'),
  unknownKernelMessage('made-up-kernel', lookup));
check('and a known kernel has nothing to report', unknownKernelMessage('codex', lookup) === null);

// A kernel registered at runtime through the environment must be driven correctly
// without its declarer knowing this module exists.
{
  const envEntry = { id: 'brand-new', transport: 'acp', resume: true, liveTerminals: false };
  const caps = kernelCapabilities(envEntry);
  check('a kernel registered through the environment gets the ACP driver',
    caps.driver === 'acp' && caps.resumable === true, JSON.stringify(caps));
  const cliEntry = { id: 'new-shim', transport: 'cli', resume: true };
  check('and a CLI kernel is driven through the same adapter',
    turnDriver(cliEntry) === 'acp', turnDriver(cliEntry));
  const sdkEntry = { id: 'new-sdk', transport: 'sdk', resume: false };
  check('an SDK kernel is not silently sent to the ACP adapter',
    turnDriver(sdkEntry) === 'sdk', turnDriver(sdkEntry));
}

// ---- the inputs that must not be guessed at ---------------------------------

check('an explicit null transport is not reinterpreted as the tier',
  turnDriver({ id: 'x', tier: 'acp', transport: null }) === 'none',
  'an unsupported entry must stay unsupported');
check('a missing transport is not invented',
  turnDriver({ id: 'x', tier: 'acp' }) === 'none', 'tier is not transport');
check('an empty id is unknown rather than a crash',
  turnDriver('', lookup) === 'none' && kernelCapabilities('', lookup).known === false);
check('a null entry is unknown rather than a crash',
  kernelCapabilities(null, lookup).known === false && canResume(null, lookup) === false);
check('no lookup at all is unknown rather than a crash',
  turnDriver('codex') === 'none', 'asking by id without a table cannot be answered');

// ---- the shape stays complete -----------------------------------------------

{
  const caps = kernelCapabilities('codex', lookup);
  const keys = Object.keys(caps).sort().join(',');
  check('a capabilities object is complete, so nothing has to be re-derived by a caller',
    keys === 'cancel,driver,id,known,liveTerminals,modelRoute,ownsSessionStore,resumable,transport',
    keys);
  check('the id survives the round trip', caps.id === 'codex', caps.id);
  check('and the transport is reported as declared', caps.transport === 'app-server', caps.transport);
  check('and the model route is one the manager can honour',
    MODEL_ROUTES.includes(caps.modelRoute), caps.modelRoute);
}

{
  // The three model routes are genuinely different answers, so the table must not
  // collapse them: Codex reads its own config, an ACP kernel has a pinnable default,
  // and the SDK runtime has its own route.
  const routes = {
    codex: modelRoute('codex', lookup),
    opencode: modelRoute('opencode', lookup),
    dsh: modelRoute('dsh', lookup),
  };
  check('each family declares its own model route',
    routes.codex === 'kernel-config' && routes.opencode === 'pinned' && routes.dsh === 'default',
    JSON.stringify(routes));
  check('and an unknown kernel falls back to the runtime default rather than to a guess',
    modelRoute('made-up-kernel', lookup) === 'default');
}

{
  // A kernel owned by the kernel itself, versus one whose runtime is ours.
  check('the app-server kernel owns its session store',
    ownsSessionStore('codex', lookup) === true);
  check('the SDK kernel does not', ownsSessionStore('dsh', lookup) === false);
  check('and an unknown kernel definitely does not',
    ownsSessionStore('nope', lookup) === false);
}

{
  const styles = [...new Set(ids.map((id) => cancelStyle(id, lookup)))];
  check('every kernel has a stated way to stop, none of them guessed',
    styles.every((s) => ['ask-kernel', 'kill-process', 'close-session', 'none'].includes(s)),
    styles.join(','));
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
