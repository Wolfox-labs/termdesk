/**
 * The machine token: two rules, both of which CI depends on.
 *
 * Every end-to-end suite here authenticates as if it were the phone, with the token the
 * agent writes to `~/.termdesk/token`. On CI that file does not exist, so each suite used
 * to die with ENOENT inside a file whose name says nothing about tokens - and the first
 * time this workflow runs, that is the whole `pc-agent` job. The rule is now "read it
 * through the helper, which skips out loud", and a rule nobody checks is a habit, not a
 * rule. Hence this test.
 *
 * Two things are checked, and neither is about the helper's code:
 *
 *   1. behaviour: with no token file, the helper prints a skip and exits 0 - asserted by
 *      running it in a sandbox whose home directory is empty;
 *   2. reach: nothing under `tools/` reads that file directly any more, and enough suites
 *      import the helper that the first rule cannot have quietly stopped applying.
 *
 * Free, offline, no agent, no model.
 *
 *   node tools/machine-token-test.js
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const HELPER = path.join(here, 'lib', 'machine-token.mjs');

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** Every tool file, recursively, minus the helper itself. */
function toolFiles(dir = here) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return toolFiles(full);
    if (!/\.(js|mjs)$/.test(entry.name)) return [];
    if (path.resolve(full) === path.resolve(HELPER)) return [];
    return [full];
  });
}

const relative = (file) => path.relative(here, file).split(path.sep).join('/');

// ---- 1. behaviour: no token, no crash ----------------------------------------

{
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-tokenless-'));
  const probe = path.join(sandbox, 'probe.mjs');
  // `os.homedir()` follows USERPROFILE on Windows and HOME elsewhere; both are pointed at
  // the empty sandbox so the check is about the missing file, not about this machine.
  fs.writeFileSync(
    probe,
    `import { machineTokenOrSkip } from ${JSON.stringify(pathToFileURL(HELPER).href)};\n`
    + "console.log('token=' + machineTokenOrSkip('probe'));\n",
  );
  let out = '';
  let code = 0;
  try {
    out = execFileSync(process.execPath, [probe], {
      encoding: 'utf8',
      env: { ...process.env, USERPROFILE: sandbox, HOME: sandbox },
    });
  } catch (err) {
    out = `${err.stdout ?? ''}${err.stderr ?? ''}`;
    code = err.status ?? 1;
  }
  check('with no token file it exits 0 instead of throwing', code === 0, `exit=${code}`);
  check('and says which file was missing, so the skip is not silent',
    out.includes('skip probe') && out.includes('.termdesk'),
    out.trim().split('\n').pop() ?? '');
  fs.rmSync(sandbox, { recursive: true, force: true });
}

// ---- 2. reach: one place reads it --------------------------------------------

/**
 * Reads that are not of *this machine's* token, and why.
 *
 * A rule with a hidden exception is not a rule, so the exception is named here and printed
 * with the result. Anything not on this list has to go through the helper.
 */
const ALLOWED = new Map([
  ['local-mode-test.js',
    'reads the token the agent wrote into a throwaway HOME, to prove it lands under the host\'s own home'],
]);

{
  const files = toolFiles();
  const readers = files.filter((file) => {
    if (ALLOWED.has(relative(file))) return false;
    const text = fs.readFileSync(file, 'utf8');
    // The read, however it is spelled: readFileSync of a path that mentions the token.
    return /readFileSync\([^)]*token/i.test(text)
      && /termdesk/i.test(text);
  });
  check('no tool reads ~/.termdesk/token directly', readers.length === 0,
    readers.map(relative).join(', ') || `only the helper (${ALLOWED.size} named exception)`);

  const importers = files.filter((file) => fs.readFileSync(file, 'utf8').includes('machine-token.mjs'));
  check('and the suites that need it go through the helper', importers.length >= 10,
    `${importers.length} suites import it`);
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
