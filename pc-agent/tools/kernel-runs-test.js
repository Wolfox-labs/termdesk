/**
 * Which running processes count as "an agent the person started themselves".
 *
 * The failure this guards against is a list the phone draws that is either empty
 * when something IS running, or full of things that are not agents at all. Both
 * are worse than no list:
 *
 *   - matching too loosely (say, every `node`) would fill the screen with the
 *     login helper and the editor's language server;
 *   - matching too strictly (a full path) would miss the executable entirely,
 *     because a process reports only its basename;
 *   - counting the agent's own children would show every conversation twice, once
 *     as a live chat and once as a process.
 *
 * Free: no process is spawned, no kernel is run.
 *
 *   node tools/kernel-runs-test.js
 */
import {
  executableFor,
  processNameOf,
  externalKernelRuns,
  describeExternalRuns,
  withDescendants,
} from '../src/kernelruns.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const proc = (pid, name, memBytes = 1000) => ({ pid, name, memBytes, cpuSeconds: 0.5 });
const kernel = (id, label, available = true) => ({ id, label, available });

// ---- reading a process name out of a path -----------------------------------

check('a Windows path becomes the bare process name',
  processNameOf('C:\\Users\\x\\AppData\\Roaming\\npm\\codex.exe') === 'codex',
  processNameOf('C:\\Users\\x\\AppData\\Roaming\\npm\\codex.exe'));
check('a POSIX path becomes the bare process name',
  processNameOf('/usr/local/bin/opencode') === 'opencode');
check('a name that is already bare is left alone', processNameOf('dsh') === 'dsh');
check('an empty path has no name', processNameOf('') === null && processNameOf(null) === null);

// ---- what each kernel looks like as a process --------------------------------

check('codex is matched by name', executableFor('codex') === 'codex');
check('a kernel we cannot name is not guessed at', executableFor('antigravity') === null);

// ---- the list itself ---------------------------------------------------------

{
  const runs = externalKernelRuns(
    [proc(11, 'codex'), proc(22, 'explorer'), proc(33, 'node'), proc(44, 'dsh')],
    [kernel('codex', 'Codex'), kernel('dsh', 'DeepSeek Harness')],
  );
  check('only agent processes are picked up, in either direction',
    runs.map((r) => r.kernelId).sort().join(',') === 'codex,dsh',
    runs.map((r) => `${r.kernelId}#${r.pid}`).join(' '));
  check('a live chat on the same kernel is not confused with a process',
    !runs.some((r) => r.name === 'explorer' || r.name === 'node'));
}

{
  // The one that would double every row: this agent's own children.
  const runs = externalKernelRuns(
    [proc(11, 'codex'), proc(22, 'codex')],
    [kernel('codex', 'Codex')],
    new Set([11]),
  );
  check('the agent\'s own process is excluded', runs.length === 1 && runs[0].pid === 22,
    runs.map((r) => r.pid).join(','));
}

{
  const runs = externalKernelRuns([proc(11, 'node')], [kernel('qoder', 'QoderWork CN')]);
  check('a wrapper launched as node is not reported as the kernel', runs.length === 0);
}

{
  const runs = externalKernelRuns(
    [proc(11, 'codex')],
    [kernel('codex', 'Codex', false)],
  );
  check('a kernel that is not installed here is not matched', runs.length === 0);
}

{
  const runs = externalKernelRuns(
    [proc(11, 'codex.exe')],
    [kernel('codex', 'Codex')],
  );
  check('a process name reported with .exe still matches', runs.length === 1);
}

{
  const runs = externalKernelRuns(
    [proc(11, 'codex', 50), proc(22, 'dsh', 500)],
    [kernel('codex', 'Codex'), kernel('dsh', 'DeepSeek Harness')],
  );
  check('the heaviest process is first, because that is the one worth noticing',
    runs[0].pid === 22, runs.map((r) => `${r.pid}:${r.memBytes}`).join(' '));
}

{
  const runs = externalKernelRuns([proc(11, 'codex')], [kernel('codex', 'Codex')]);
  check('a run is never offered as attachable: the agent holds no handle to it',
    runs[0].attachable === false);
  check('and it carries the kernel it belongs to', runs[0].kernelId === 'codex' && runs[0].label === 'Codex');
}

// ---- degenerate inputs -------------------------------------------------------

check('no processes is an empty list', externalKernelRuns([], [kernel('codex', 'Codex')]).length === 0);
check('no kernels is an empty list', externalKernelRuns([proc(11, 'codex')], []).length === 0);
check('nothing to report says so instead of saying nothing',
  describeExternalRuns([]).length > 0, describeExternalRuns([]));
check('a summary counts by kernel', describeExternalRuns([
  { label: 'Codex' }, { label: 'Codex' }, { label: 'DSH' },
]).includes('Codex×2'), describeExternalRuns([{ label: 'Codex' }, { label: 'Codex' }, { label: 'DSH' }]));

// ---- the agent's own children, including the ones below the wrapper ----------
//
// This is the bug that reached a phone: "MiMo Code" was listed as an agent running on
// the machine, and it was TermDesk's OWN kernel. The agent recorded `node .../bin/mimo`
// as its child, but a process list reports `mimo.exe` one level further down, so
// excluding only the recorded pid excluded nothing that mattered.

{
  // The measured chain, verbatim from the machine that produced the bug.
  const chain = [
    { pid: 46068, name: 'node', parentPid: 18708 }, // the agent itself
    { pid: 42160, name: 'node', parentPid: 46068 }, // `node .../bin/mimo` (recorded)
    { pid: 44280, name: 'mimo', parentPid: 42160 }, // `mimo.exe acp` (what the list shows)
  ];
  const ours = withDescendants(chain, [42160]);

  check('a grandchild of a recorded pid is recognised as ours',
    ours.has(44280), 'this is the exact process the phone was shown as a stranger');
  check('and the recorded pid itself stays excluded', ours.has(42160));
  check('walking down does not walk UP into the agent and its shell',
    !ours.has(46068) && !ours.has(18708), 'the agent is not one of its own conversations');
  check('the exclusion set is what externalKernelRuns needs to say nothing',
    externalKernelRuns(chain, [kernel('mimo', 'MiMo Code')], ours).length === 0,
    JSON.stringify(externalKernelRuns(chain, [kernel('mimo', 'MiMo Code')], ours)));
}

{
  // Without this, the fix would be a regression: every kernel would vanish.
  const other = [
    { pid: 46068, name: 'node', parentPid: 1 },
    { pid: 900, name: 'mimo', parentPid: 1 }, // somebody else's kernel
  ];
  check('a kernel that is NOT ours is still reported',
    externalKernelRuns(other, [kernel('mimo', 'MiMo Code')], withDescendants(other, [46068])).length === 1);
}

{
  // A deeper chain, and two recorded roots.
  const deep = [
    { pid: 1, name: 'a', parentPid: 0 },
    { pid: 2, name: 'b', parentPid: 1 },
    { pid: 3, name: 'c', parentPid: 2 },
    { pid: 4, name: 'd', parentPid: 3 },
    { pid: 5, name: 'e', parentPid: 1 },
  ];
  const ours = withDescendants(deep, [1]);
  check('a deep chain is walked to the bottom',
    [1, 2, 3, 4, 5].every((pid) => ours.has(pid)), [...ours].join(','));
}

check('no roots means nothing is excluded',
  withDescendants([{ pid: 1, name: 'a', parentPid: 0 }], []).size === 0);
check('a root that is not in the list is still excluded',
  withDescendants([{ pid: 1, name: 'a', parentPid: 0 }], [999]).has(999),
  'our own pid must be excluded even if the process list is capped before it');
check('no parent links at all still excludes the roots themselves',
  withDescendants([{ pid: 1, name: 'a' }, { pid: 2, name: 'b' }], [1]).size === 1,
  'an older collector must not silently stop excluding anything');
check('pid 0 is never treated as a parent',
  withDescendants([{ pid: 1, name: 'a', parentPid: 0 }, { pid: 2, name: 'b', parentPid: 0 }], [1]).size === 1,
  'otherwise every orphan would look like a child of pid 0');
check('junk pids are skipped rather than walked',
  withDescendants([{ pid: 'x', name: 'a' }, { pid: -5, name: 'b' }], [1]).size === 1);
check('an empty process list returns just the roots',
  withDescendants([], [7]).has(7));

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
