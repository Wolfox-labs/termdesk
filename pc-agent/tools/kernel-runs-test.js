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
import { executableFor, processNameOf, externalKernelRuns, describeExternalRuns } from '../src/kernelruns.js';

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

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
