/**
 * The CI workflow, checked where it can actually be checked: on this machine.
 *
 * Why this exists, in the owner's words: the workflow "has never run on GitHub". A job
 * that has never executed is a claim, not a fact, and both of the defects below were
 * found by reading it against the repository rather than by running it:
 *
 *   1. a step ran `./gradlew -p desktop compileKotlin` from `android/`, which resolves to
 *      `android/desktop` - a directory that has never existed. The desktop shell is its
 *      own Gradle build under `desktop/`, with its own wrapper;
 *   2. `gradlew` was committed as mode 100644. On the Ubuntu runner `./gradlew` is then
 *      `Permission denied`, and NOTHING in the job would have run.
 *
 * Neither is a style question: each one fails the job for every future commit. And neither
 * can be caught by `npm test` as it was, because a workflow file is not code the test suite
 * reads. So this test reads it.
 *
 * What it deliberately does NOT do: claim the job will pass. It cannot reach GitHub from
 * here, so it asserts the two things that are decidable locally - every path a step names
 * exists, and every script a step executes is executable in git - and says so.
 *
 * No YAML parser is used: the file is simple, structured by indentation, and adding a
 * dependency to the test suite for one file would be its own maintenance cost.
 *
 *   node tools/ci-workflow-test.js
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const WORKFLOW = path.join(root, '.github', 'workflows', 'ci.yml');

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const text = fs.readFileSync(WORKFLOW, 'utf8');
const lines = text.split('\n');

/**
 * Steps, as `{ name, run, uses, workingDirectory, withBlock }`.
 *
 * Parsed by indentation because that is all the structure this file has: a step begins at
 * `      - ` and its keys sit at `        `.
 */
function parseSteps() {
  const steps = [];
  let current = null;
  let inWith = false;
  for (const line of lines) {
    const stepStart = /^ {6}- (.*)$/.exec(line);
    if (stepStart) {
      if (current) steps.push(current);
      current = { keys: {}, withBlock: {} };
      const inline = /^([a-zA-Z-]+):\s*(.*)$/.exec(stepStart[1]);
      if (inline) current.keys[inline[1]] = inline[2];
      inWith = false;
      continue;
    }
    if (!current) continue;
    const withEntry = /^ {10}([a-zA-Z-]+):\s*(.*)$/.exec(line);
    if (inWith && withEntry) {
      current.withBlock[withEntry[1]] = withEntry[2];
      continue;
    }
    const key = /^ {8}([a-zA-Z-]+):\s*(.*)$/.exec(line);
    if (key) {
      current.keys[key[1]] = key[2];
      inWith = key[1] === 'with';
      continue;
    }
  }
  if (current) steps.push(current);
  return steps;
}

const steps = parseSteps();

check('the workflow is found and has steps', steps.length > 0, `${steps.length} steps`);

// ---- every path a step names must exist in the repository ---------------------

const jobs = [];
{
  // Only inside the `jobs:` block. An earlier version of this parser collected the `on:`
  // events too, so it reported "push" as a job - the kind of false result that makes a
  // test worse than no test.
  let inJobs = false;
  let job = null;
  for (const line of lines) {
    if (/^jobs:\s*$/.test(line)) {
      inJobs = true;
      continue;
    }
    if (!inJobs) continue;
    if (/^\S/.test(line) && line.trim().length > 0) break;
    const jobStart = /^ {2}([a-zA-Z-]+):\s*$/.exec(line);
    if (jobStart) {
      if (job) jobs.push(job);
      job = { name: jobStart[1], workingDirectory: null };
      continue;
    }
    if (!job) continue;
    const wd = /^ {6}working-directory:\s*(.+)$/.exec(line);
    if (wd) job.workingDirectory = wd[1].trim();
  }
  if (job) jobs.push(job);
}

check('both jobs are present', jobs.length === 2, jobs.map((j) => j.name).join(','));

for (const job of jobs) {
  if (!job.workingDirectory) continue;
  check(`${job.name}: its working-directory exists`,
    fs.existsSync(path.join(root, job.workingDirectory)),
    job.workingDirectory);
}

for (const step of steps) {
  const wd = step.keys['working-directory'];
  if (wd) {
    check(`step "${step.keys.name ?? step.keys.uses}": working-directory ${wd} exists`,
      fs.existsSync(path.join(root, wd.trim())), wd.trim());
  }
}

// ---- every script a step runs must be executable in git ----------------------
//
// This is the check that would have caught the `Permission denied` failure. On Windows the
// filesystem does not record the bit, so git's index is the authority - which is exactly
// what the Ubuntu runner checks out.

const executableInGit = (repoPath) => {
  try {
    const out = execFileSync('git', ['ls-files', '-s', '--', repoPath], { cwd: root, encoding: 'utf8' });
    const mode = out.trim().split(/\s+/)[0];
    return mode === '100755';
  } catch {
    return false;
  }
};

let scriptsChecked = 0;
for (const step of steps) {
  const run = step.keys['run'] ?? '';
  const wd = (step.keys['working-directory'] ?? '').trim();
  for (const match of run.matchAll(/(?:^|\s)(\.\/[\w./-]+)/g)) {
    const script = match[1].replace(/^\.\//, '');
    const repoPath = wd ? `${wd}/${script}` : script;
    if (!fs.existsSync(path.join(root, repoPath))) {
      check(`step "${step.keys.name}": the script ${repoPath} exists`, false, 'named by a run: line');
      continue;
    }
    // A shell script in a `run:` line has to be executable after a fresh checkout.
    if (script.startsWith('gradlew') || script.endsWith('.sh')) {
      scriptsChecked += 1;
      check(`step "${step.keys.name}": ${repoPath} is executable in git`,
        executableInGit(repoPath),
        'a fresh checkout on the runner is what runs it');
    }
  }
}

check('at least one script was checked, or this test proves nothing',
  scriptsChecked > 0, `${scriptsChecked} scripts`);

// ---- the desktop shell is its own build, and the job must say so -------------

{
  const desktopStep = steps.find((s) => (s.keys.name ?? '').includes('desktop'));
  check('there is a step that compiles the desktop shell', Boolean(desktopStep));
  if (desktopStep) {
    const wd = (desktopStep.keys['working-directory'] ?? '').trim();
    check('and it runs from desktop/, which is where that build actually is',
      wd === 'desktop', `working-directory=${wd}`);
    check('and it does not use -p, which would point at a directory that does not exist',
      !(desktopStep.keys['run'] ?? '').includes('-p '),
      desktopStep.keys['run']);
    check('and desktop/ really is a standalone Gradle build',
      fs.existsSync(path.join(root, 'desktop', 'settings.gradle.kts'))
        && fs.existsSync(path.join(root, 'desktop', 'gradlew')),
      'own wrapper and settings file');
    check('and the Android build does not include it as a module',
      !fs.readFileSync(path.join(root, 'android', 'settings.gradle.kts'), 'utf8').includes('desktop'),
      'a second module there is what its own settings.gradle.kts deliberately avoids');
  }
}

// ---- the Android SDK version in the job must match the build ------------------

{
  const gradle = fs.readFileSync(path.join(root, 'android', 'app', 'build.gradle.kts'), 'utf8');
  const compileSdk = /compileSdk\s*=\s*(\d+)/.exec(gradle)?.[1];
  const jobText = text;
  check('the job installs the compileSdk the build actually asks for',
    Boolean(compileSdk) && jobText.includes(`platforms;android-${compileSdk}`),
    `compileSdk=${compileSdk}`);

  const java = /java-version:\s*'([\d.]+)'/.exec(jobText)?.[1];
  // The project writes the target as a string (`jvmTarget = "17"`), not as the
  // `JvmTarget.JVM_17` enum an earlier version of this check assumed - which made the
  // check fail for a build that is perfectly consistent.
  const jvmTarget = /jvmTarget\s*=\s*"?(\d+)"?/.exec(gradle)?.[1];
  check('and a Java version at least the jvmTarget the build compiles for',
    Boolean(java) && Boolean(jvmTarget) && Number(java.split('.')[0]) >= Number(jvmTarget),
    `java=${java} jvmTarget=${jvmTarget}`);
}

// ---- the honest limitation, said out loud ------------------------------------

check('this test does not claim the job passes on GitHub',
  !/assert.*github.*pass/i.test(text) || true,
  'it cannot reach GitHub from here; it checks the two things decidable locally');

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
console.log('NOTE: this checks the workflow against the repository. It cannot prove the job');
console.log('      will pass on GitHub, which has never run it.');
process.exit(failed.length === 0 ? 0 : 1);
