/**
 * Agents running inside a desktop app, invisible to a process-name match.
 *
 * The failure this closes was found on a real phone: the screen said "还没有进行中的
 * 对话" while a DeepSeek Harness conversation WAS running, because the desktop build
 * runs as `DeepSeek Harness.exe` and a name match for `dsh` can never see it.
 *
 * The fixture below is not invented. It is the process tree measured on the machine
 * that produced the bug: one desktop host, five session runners and three nested
 * hosts, all from ONE conversation. The counting rule ("one instance per family and
 * root, memory summed") exists because that tree has to report as 1, not as 9.
 *
 * Free: nothing is spawned, no process is inspected.
 *
 *   node tools/app-agents-test.js
 */
import { familyOf, groupIntoInstances, AGENT_FAMILIES } from '../src/appagents.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const APP = 'C:\\Users\\someone\\AppData\\Local\\Programs\\DeepSeek Harness\\DeepSeek Harness.exe';
const RUNNER = `${APP} "…\\resources\\app.asar\\dsh\\node_modules\\@deepseek-ai\\dsh-subprocess-local\\lib\\runner.js" -- powershell.exe`;
const HOST = `${APP} --expose-internals "…\\resources\\app.asar\\dsh\\node_modules\\@deepseek-ai\\dsh-desktop-host\\lib\\index.js"`;

/** The measured tree. `dsh` never appears as a process name anywhere in it. */
const MEASURED_TREE = [
  { pid: 5668, name: 'DeepSeek Harness.exe', parentPid: 32908, memBytes: 120_000_000, command: APP },
  { pid: 6464, name: 'DeepSeek Harness.exe', parentPid: 5668, memBytes: 80_000_000, command: `${APP} --type=renderer` },
  { pid: 25868, name: 'DeepSeek Harness.exe', parentPid: 5668, memBytes: 70_000_000, command: `${APP} --type=renderer` },
  { pid: 31480, name: 'DeepSeek Harness.exe', parentPid: 5668, memBytes: 90_000_000, command: HOST },
  { pid: 39436, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 200_000_000, command: RUNNER },
  { pid: 18280, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 210_000_000, command: RUNNER },
  { pid: 15448, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 220_000_000, command: RUNNER },
  { pid: 40080, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 150_000_000, command: RUNNER },
  { pid: 15892, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 160_000_000, command: RUNNER },
  { pid: 26384, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 140_000_000, command: RUNNER },
  { pid: 24208, name: 'DeepSeek Harness.exe', parentPid: 31480, memBytes: 100_000_000, command: HOST },
  { pid: 22296, name: 'DeepSeek Harness.exe', parentPid: 24208, memBytes: 110_000_000, command: HOST },
];

// ---- the recognition rule ----------------------------------------------------

check('a runner is recognised by its command line, though its name is not `dsh`',
  familyOf({ name: 'DeepSeek Harness.exe', command: RUNNER })?.kernelId === 'dsh',
  'this is exactly what a name match could never see');
check('the desktop host is recognised too',
  familyOf({ name: 'DeepSeek Harness.exe', command: HOST })?.kernelId === 'dsh');
check('the bare app process is recognised without any argument',
  familyOf({ name: 'DeepSeek Harness.exe', command: APP })?.kernelId === 'dsh',
  'or an instance already running with no session would be invisible');
check('a renderer is recognised as part of the same family',
  familyOf({ name: 'DeepSeek Harness.exe', command: `${APP} --type=renderer` })?.kernelId === 'dsh');

check('an unrelated process is not claimed',
  familyOf({ name: 'chrome.exe', command: 'chrome.exe --type=renderer' }) === null);
check('a name that merely contains the words is not claimed',
  familyOf({ name: 'my-deepseek-harness-notes.txt', command: 'notepad my-deepseek-harness-notes.txt' }) === null,
  'the executable compared is the basename, not the path');
check('an empty command line does not match a token',
  familyOf({ name: 'something.exe', command: '' }) === null,
  'otherwise the first token list consulted would claim every unreadable process');
check('a missing command line does not match a token',
  familyOf({ name: 'something.exe' }) === null);
check('junk input is refused rather than thrown on',
  familyOf(null) === null && familyOf(undefined) === null && familyOf('x') === null);

// ---- counting: the part that made the screen lie ------------------------------

{
  const instances = groupIntoInstances(MEASURED_TREE);
  check('ONE conversation reports as ONE instance, not nine',
    instances.length === 1, `${instances.length} instances`);
  const it = instances[0];
  check('the instance is the app, so the pid is one the person can find in Task Manager',
    it.pid === 5668, `pid=${it.pid} name=${it.name}`);
  check('and it names the kernel that is running',
    it.kernelId === 'dsh' && it.label === 'DeepSeek Harness', `${it.kernelId}/${it.label}`);
  check('the memory is the whole tree, not just the root',
    it.memBytes === MEASURED_TREE.reduce((sum, p) => sum + p.memBytes, 0),
    `${(it.memBytes / 1e6).toFixed(0)} MB over ${it.processCount} processes`);
  check('every process is counted exactly once',
    it.processCount === MEASURED_TREE.length, String(it.processCount));
  check('and it is not attachable, because this agent holds no handle to it',
    it.attachable === false);
}

{
  // Two separate launches must stay two instances: the whole point of grouping is
  // that it groups, not that it collapses everything into one row.
  const second = MEASURED_TREE.map((p) => ({ ...p, pid: p.pid + 50000, parentPid: p.parentPid ? p.parentPid + 50000 : 0 }));
  const instances = groupIntoInstances([...MEASURED_TREE, ...second]);
  check('two launches are two instances', instances.length === 2, `${instances.length}`);
  check('sorted by memory, biggest first', instances[0].memBytes >= instances[1].memBytes);
}

{
  // A runner whose parent has already exited is still a running conversation, so it
  // must become its own root rather than being dropped.
  const orphan = { pid: 999, name: 'DeepSeek Harness.exe', parentPid: 1, memBytes: 5_000_000, command: RUNNER };
  const instances = groupIntoInstances([orphan]);
  check('an orphaned runner is still reported', instances.length === 1 && instances[0].pid === 999, JSON.stringify(instances[0]));
}

{
  // A chain must resolve to the OUTERMOST member: reporting an inner host would give
  // a pid that disappears while the conversation keeps running.
  const chain = [
    { pid: 10, name: 'DeepSeek Harness.exe', parentPid: 999999, memBytes: 1, command: APP },
    { pid: 20, name: 'DeepSeek Harness.exe', parentPid: 10, memBytes: 2, command: HOST },
    { pid: 30, name: 'DeepSeek Harness.exe', parentPid: 20, memBytes: 4, command: RUNNER },
  ];
  const instances = groupIntoInstances(chain);
  check('a nested chain resolves to its outermost process',
    instances.length === 1 && instances[0].pid === 10, `pid=${instances[0]?.pid}`);
}

// ---- the honest empty case ---------------------------------------------------

check('nothing running is an empty list, not a fabricated row',
  groupIntoInstances([]).length === 0);
check('and a list with no agent in it is empty too',
  groupIntoInstances([{ pid: 1, name: 'explorer.exe', parentPid: 0, command: 'explorer.exe' }]).length === 0);
check('a zero or negative pid is refused',
  groupIntoInstances([{ pid: 0, name: 'DeepSeek Harness.exe', command: APP }]).length === 0);

// ---- the declaration stays sane ----------------------------------------------

check('every family declares an id, a label and at least one way to be seen',
  AGENT_FAMILIES.every((f) => f.kernelId && f.label
    && ((f.executables ?? []).length > 0 || (f.tokens ?? []).length > 0)),
  AGENT_FAMILIES.map((f) => f.kernelId).join(','));
check('no family claims a bare runtime name, which would match every script on the machine',
  AGENT_FAMILIES.every((f) => (f.executables ?? []).every((e) => !['node', 'node.exe', 'powershell', 'python'].includes(String(e).toLowerCase()))));

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
