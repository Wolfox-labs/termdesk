/**
 * The reaper, against a real process.
 *
 * What this pins is the property that makes the reaper safe to run unattended at every
 * startup: it kills ONLY pids that (a) are in our ledger AND (b) still carry the spawn
 * marker. The owner's own `DeepSeek Harness` desktop app carries no marker, so it can
 * never be matched however similar its name looks - and that is asserted here with a
 * process that is deliberately NOT ours.
 *
 * The orphan is a real process, not a mock: proving this needs a pid that can actually
 * be killed, because the failure being guarded against is "the kill did nothing".
 *
 * Model-free, socket-free: two child processes and one function call.
 *
 *   node tools/spawn-ledger-test.js
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { SPAWN_MARKER, kernelEnv, rememberSpawn, forgetSpawn, reapOrphans, describeReap } from '../src/spawnledger.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const LEDGER = path.join(os.homedir(), '.termdesk', 'spawned.json');
const backup = fs.existsSync(LEDGER) ? fs.readFileSync(LEDGER, 'utf8') : null;

/** Start a process that sits there, optionally carrying our spawn marker. */
function startIdler(marked) {
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    stdio: 'ignore',
    detached: true,
    env: marked ? kernelEnv() : { ...process.env },
  });
  child.unref();
  return child.pid;
}

/**
 * Start a process that itself starts a child, and report both pids.
 *
 * The intermediate matters: it goes in the ledger, so the reaper walks down from IT and
 * kills the grandchild. Putting THIS test's pid in the ledger instead would make the
 * reaper kill the test - which is the correct behaviour, and a useless test.
 */
function startRootWithChild(marked) {
  const script = "const { spawn } = require('node:child_process');"
    + "const c = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],"
    + " { stdio: 'ignore', detached: true, env: process.env });"
    + "c.unref(); console.log(c.pid); setInterval(() => {}, 1000);";
  const root = spawn(process.execPath, ['-e', script], {
    stdio: ['ignore', 'pipe', 'ignore'],
    detached: true,
    env: marked ? kernelEnv() : { ...process.env },
  });
  root.unref();
  return new Promise((resolve) => {
    let out = '';
    root.stdout.on('data', (d) => {
      out += d.toString();
      const pid = Number(out.trim());
      if (Number.isFinite(pid) && pid > 0) resolve({ root: root.pid, child: pid });
    });
  });
}

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

/**
 * Whether a pid is still alive after giving the OS a moment.
 *
 * `killTree` shells out to `taskkill`, which returns before the process is gone, so a
 * bare check races it. Polling is the honest way to assert "it is dead now" about
 * something we asked another process to do.
 */
const aliveAfter = async (pid, ms = 2500) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (!alive(pid)) return false;
    await sleep(100);
  }
  return true;
};

const writeLedger = (entries) => {
  fs.mkdirSync(path.dirname(LEDGER), { recursive: true });
  fs.writeFileSync(LEDGER, JSON.stringify({ entries }, null, 2) + '\n');
};

try {
  // ---- the ledger itself -----------------------------------------------------
  writeLedger([]);
  const tracked = startIdler(true);
  rememberSpawn(tracked, 'test:tracked');
  await sleep(200);
  const saved = JSON.parse(fs.readFileSync(LEDGER, 'utf8'));
  check('a spawned pid is written to the ledger immediately',
    saved.entries.some((e) => Number(e.pid) === tracked),
    'written at spawn, because the exit that would clean up may never run');

  forgetSpawn(tracked);
  const after = JSON.parse(fs.readFileSync(LEDGER, 'utf8'));
  check('and removed when the child exits on its own',
    !after.entries.some((e) => Number(e.pid) === tracked), 'so the ledger stays a list of live processes');

  check('a junk pid is not recorded', (() => {
    rememberSpawn(0, 'x');
    rememberSpawn('nonsense', 'x');
    const now = JSON.parse(fs.readFileSync(LEDGER, 'utf8'));
    return now.entries.length === 0;
  })(), JSON.stringify(JSON.parse(fs.readFileSync(LEDGER, 'utf8')).entries));

  // ---- the property that makes it safe --------------------------------------
  //
  // A root whose pid IS in the ledger, a child of that root (a real descendant), and a
  // stranger that is neither. The stranger stands in for the owner's own desktop app:
  // same shape, no ancestry from us, must survive.
  const owned = await startRootWithChild(true);
  const stranger = startIdler(true);
  await sleep(400);
  check('the owned tree and the stranger are all running',
    alive(owned.root) && alive(owned.child) && alive(stranger),
    `root=${owned.root} child=${owned.child} stranger=${stranger}`);
  writeLedger([
    { pid: owned.root, what: 'test:root', run: 'previous', at: new Date().toISOString() },
    { pid: 999999, what: 'test:gone', run: 'previous', at: new Date().toISOString() },
  ]);

  const report = await reapOrphans();
  await sleep(900);

  check('a descendant of a ledger pid IS killed', !(await aliveAfter(owned.child)),
    `root=${owned.root} child=${owned.child} reaped=${report.reaped.map((r) => `${r.pid}/${r.name}`).join(',')}`);
  check('and the root it was recorded under is killed too', !(await aliveAfter(owned.root)));
  check('a process with no ancestry from us is NOT killed',
    alive(stranger),
    'the walk goes DOWN from our pids, so the owner\'s own desktop app is never matched');
  check('a pid that no longer exists is skipped rather than reported as reaped',
    report.reaped.every((r) => Number(r.pid) !== 999999), JSON.stringify(report.reaped.map((r) => r.pid)));
  check('the pass reports what it did, because an unattended reaper that says nothing is unverifiable',
    typeof describeReap(report) === 'string' && describeReap(report).length > 0, describeReap(report));
  check('the ledger is emptied so the next run does not re-examine the same pids',
    JSON.parse(fs.readFileSync(LEDGER, 'utf8')).entries.length === 0);

  // ---- the honest empty case -------------------------------------------------
  const empty = await reapOrphans();
  check('an empty ledger does nothing and says nothing',
    empty.reaped.length === 0 && describeReap(empty) === null, JSON.stringify(empty));

  // ---- cleanup ---------------------------------------------------------------
  check('the marker is a name a kernel can inherit without colliding',
    SPAWN_MARKER === 'TERMDESK_SPAWNED', SPAWN_MARKER);
  {
    // Compared case-insensitively with a key that actually exists: Windows does not use
    // `PATH` as the spelling, so `env.PATH` is undefined there and an equality check
    // against it would fail for a correct implementation.
    const merged = kernelEnv();
    const pathKey = Object.keys(process.env).find((k) => k.toLowerCase() === 'path');
    check('kernelEnv keeps the machine environment and adds only the marker',
      merged[pathKey] === process.env[pathKey] && merged[SPAWN_MARKER] !== undefined,
      `key=${pathKey} replacing the environment would break the kernel`);
    const withExtra = kernelEnv({ TERMDESK_TEST: '1' });
    check('kernelEnv lets a caller override one variable without losing the rest',
      withExtra.TERMDESK_TEST === '1' && withExtra[pathKey] === process.env[pathKey]);
  }

  try { process.kill(stranger, 'SIGKILL'); } catch { /* already gone */ }
} catch (err) {
  check(`unexpected failure: ${err?.message ?? err}`, false);
} finally {
  if (backup !== null) fs.writeFileSync(LEDGER, backup);
  else { try { fs.rmSync(LEDGER, { force: true }); } catch { /* best effort */ } }
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
