/**
 * The terminal's POSIX branch.
 *
 * Windows gets PowerShell; the sandbox gets bash. That second branch could only
 * ever be tested on the phone - which is how a shell loop rots unnoticed - so the
 * flavour is overridable (TERMDESK_POSIX_SHELL=1) and this test drives bash
 * directly (Git bash on Windows). No bash on the machine means the test says so
 * and passes, rather than pretending to have checked.
 *
 * Output assertions read the streamed lines, not the value a command resolves
 * with: output that arrives in its own chunk is already emitted live by the time
 * the sentinel lands, so the resolved `output` is legitimately empty. Exit codes
 * are asserted from the return value, which is the part only the sentinel knows.
 *
 * Costs nothing: no kernel, no model, no network.
 *
 *   node tools/terminal-posix-test.js
 */
import fs from 'node:fs';
import { TerminalManager } from '../src/terminal.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const CANDIDATES = [
  'C:/Program Files/Git/bin/bash.exe',
  'C:/Program Files/Git/usr/bin/bash.exe',
  '/bin/bash',
  '/usr/bin/bash',
];
const bash = CANDIDATES.find((c) => fs.existsSync(c));
if (!bash) {
  console.log('no bash on this machine - the POSIX branch cannot be verified here (not a pass)');
  process.exit(0);
}
console.log(`bash: ${bash}\n`);

process.env.TERMDESK_POSIX_SHELL = '1';
process.env.TERMDESK_SHELL = bash;

const lines = [];
const manager = new TerminalManager();
manager.attach((_sid, text, stream) => lines.push({ text, stream }));
const session = manager.create();
const seen = () => lines.map((l) => l.text).join('');

/** A hung command must be a FAIL, not a hung test run. */
const run = async (cmd, ms = 10000) => Promise.race([
  session.run(cmd),
  new Promise((resolve) => setTimeout(() => resolve({ output: '', code: -99, error: 'timeout' }), ms)),
]);

check('the session says which shell it is', /bash/.test(seen()), seen().split('\n')[0]);

const first = await run('echo hello-posix');
check('a command runs', seen().includes('hello-posix'), JSON.stringify(seen().slice(-40)));
check('the exit code of a good command is 0', first.code === 0, String(first.code));

await run('COUNT=41');
await run('COUNT=$((COUNT + 1))');
await run('echo "count=$COUNT"');
check('shell state persists between commands (that is the point of one long-lived shell)',
  seen().includes('count=42'), JSON.stringify(seen().split('\n').filter(Boolean).slice(-1)));

// (exit 3) in a subshell, not a bare exit: a bare exit would take the shell
// itself down and the next assertions would be testing a dead session.
const failing = await run('(exit 3)');
check('a failing command reports its own exit code', failing.code === 3, String(failing.code));

const missing = await run('this-command-does-not-exist-at-all');
check('an unknown command is not reported as success', missing.code !== 0, String(missing.code));
await run('echo still-here');
check('the shell survives a failed command', seen().includes('still-here'));

const multi = await run('echo one\necho two');
check('a multi-line command runs whole', multi.code === 0 && seen().includes('one') && seen().includes('two'));

await run('echo to-stderr >&2');
check('stderr stays visible instead of being dropped', seen().includes('to-stderr'));
check('stderr is labelled as stderr', lines.some((l) => l.stream === 'stderr' && l.text.includes('to-stderr')));

const before = lines.length;
await run('echo after-stderr');
check('the shell keeps working after stderr output', lines.length > before && seen().includes('after-stderr'));

manager.disposeAll();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nTerminal (POSIX): ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
