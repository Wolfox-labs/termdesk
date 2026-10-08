/**
 * Nothing this agent starts outlives it.
 *
 * The bug this closes is one the owner suspected before I could find it: stop the
 * agent, and its kernels keep running. Measured on the machine: after killing every
 * agent process, `mimo.exe` and the `node` wrapper around it were both still alive,
 * and restarting the agent spawned a SECOND `mimo` beside the first.
 *
 * Two gaps, and they need different answers:
 *
 *   1. A graceful exit cleans up (server.js handles SIGINT/SIGTERM and calls
 *      `chats.disposeAll()`). A FORCED exit does not: the task manager, a crash, or
 *      `Stop-Process -Force` all skip the handler, and on Windows a killed parent does
 *      not take its children with it. So the next start has to clean up after the
 *      previous one, which is why a ledger of what we started is written to disk.
 *   2. Even when we DO try to kill, `child.kill()` signals the process we spawned - not
 *      the real kernel behind a wrapper. `mimo` is `node bin/mimo` which execs
 *      `mimo.exe`, so the recorded pid was a layer above the process that holds the
 *      memory. Killing the tree is the only thing that actually frees the machine.
 *
 * The dangerous part of a reaper is killing something that is not ours, and the first
 * attempt at a rule did not survive contact with the platform: the marker in
 * [SPAWN_MARKER] is inherited by every kernel we start, but it cannot be READ BACK from a
 * running process - `Win32_Process` exposes a command line, not an environment, and the
 * `GetEnvironmentVariables` method belongs to WMI scripting objects that CIM does not
 * have. Both facts were checked on this machine, not assumed.
 *
 * So the rule is the ledger plus the parent chain: a pid is killed only if it descends
 * from something this agent started. Walking DOWN from our own pids is what keeps a
 * same-named stranger out of the list - the owner's own `DeepSeek Harness` desktop app,
 * the one running the conversation this was developed in, descends from `explorer.exe`
 * and can never be matched, however similar its name looks. The marker is still set,
 * because it costs nothing and it makes "is this ours?" answerable from inside a
 * descendant later.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { runJsonArray } from './exec.js';

/**
 * Inherited by every kernel this agent spawns.
 *
 * An environment variable rather than an argument, because the command line of a kernel
 * belongs to the kernel: `mimo acp` and `codex app-server` are its argv, and adding ours
 * to it would be something the kernel could one day reject. The environment is ours.
 */
export const SPAWN_MARKER = 'TERMDESK_SPAWNED';

/** Which run of the agent a pid belongs to, for reporting. */
const RUN_ID = `${process.pid}-${Date.now().toString(36)}`;

const LEDGER_DIR = path.join(os.homedir(), '.termdesk');
const LEDGER_FILE = path.join(LEDGER_DIR, 'spawned.json');

const MAX_LEDGER_ENTRIES = 200;

/**
 * Environment for a spawned kernel.
 *
 * Merged rather than replaced: a kernel needs the machine's own environment (PATH,
 * tokens, its config directories), and handing it only our marker would break it.
 */
export function kernelEnv(extra = {}) {
  return { ...process.env, [SPAWN_MARKER]: RUN_ID, ...extra };
}

/**
 * Record a pid we started, so a future run can clean it up.
 *
 * Written on every spawn rather than at exit: an exit that never runs is exactly the
 * case this exists for. The write is best-effort - a full disk must not stop a
 * conversation from starting - but a failure is logged rather than swallowed, because
 * a ledger that silently stops recording makes the reaper silently useless.
 */
export function rememberSpawn(pid, what = '') {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return;
  try {
    const state = readLedger();
    state.entries.push({ pid: n, what: String(what ?? ''), run: RUN_ID, at: new Date().toISOString() });
    if (state.entries.length > MAX_LEDGER_ENTRIES) {
      state.entries = state.entries.slice(-MAX_LEDGER_ENTRIES);
    }
    fs.mkdirSync(LEDGER_DIR, { recursive: true, mode: 0o700 });
    fs.writeFileSync(LEDGER_FILE, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  } catch (err) {
    console.error(`[termdesk] 无法记录子进程 pid=${n}：${err?.message ?? err}`);
  }
}

/** Forget a pid that has exited on its own, so the ledger does not grow forever. */
export function forgetSpawn(pid) {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return;
  try {
    const state = readLedger();
    const kept = state.entries.filter((entry) => Number(entry?.pid) !== n);
    if (kept.length === state.entries.length) return;
    state.entries = kept;
    fs.writeFileSync(LEDGER_FILE, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  } catch {
    // Forgetting is an optimisation; a failure here costs one stale entry.
  }
}

function readLedger() {
  try {
    const parsed = JSON.parse(fs.readFileSync(LEDGER_FILE, 'utf8'));
    if (Array.isArray(parsed?.entries)) return { entries: parsed.entries };
  } catch {
    // A missing or unreadable ledger means "nothing to clean up", not a failure.
  }
  return { entries: [] };
}

/** Kills a process and everything it started. */
export function killTree(pid) {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return;
  try {
    if (process.platform === 'win32') {
      // `/T` is the point: the recorded pid may be a wrapper whose child holds the
      // memory, and signalling only the wrapper leaks exactly what we came to free.
      spawn('taskkill', ['/pid', String(n), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    } else {
      process.kill(n, 'SIGKILL');
    }
  } catch {
    // Already gone, or not ours to kill any more.
  }
}

/**
 * The still-running processes that descend from pids in our ledger.
 *
 * How the reaper identifies its targets, and why it is the ledger plus the parent chain
 * rather than an environment check:
 *
 *   - the marker in [SPAWN_MARKER] is inherited by every kernel we start, but it CANNOT
 *     be read back from a running process: `Win32_Process` exposes a command line, not an
 *     environment, and the `GetEnvironmentVariables` method is a WMI-scripting one that
 *     CIM objects do not have. Verified on this machine rather than assumed.
 *   - what IS available is the parent link, and it is enough: the ledger holds pids we
 *     started, and every kernel process is a descendant of one of them. Starting from the
 *     ledger and walking DOWN is what keeps a same-named stranger - the owner's own
 *     desktop app - out of the list, because it does not descend from anything we ran.
 *
 * The walk is bounded by visiting each pid once, so a corrupt parent link cannot hang a
 * startup.
 */
const LEFTOVER_SCRIPT = (pids) => `
$roots = @(${pids.join(',')})
$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Select-Object ProcessId, ParentProcessId, Name)
$children = @{}
foreach ($p in $all) {
  $key = [int]$p.ParentProcessId
  if (-not $children.ContainsKey($key)) { $children[$key] = @() }
  $children[$key] += [int]$p.ProcessId
}
$found = @{}
$queue = New-Object System.Collections.Queue
foreach ($r in $roots) { $found[[int]$r] = $true; $queue.Enqueue([int]$r) }
while ($queue.Count -gt 0) {
  $pid0 = [int]$queue.Dequeue()
  if (-not $children.ContainsKey($pid0)) { continue }
  foreach ($c in $children[$pid0]) {
    if ($found.ContainsKey($c)) { continue }
    $found[$c] = $true
    $queue.Enqueue($c)
  }
}
$out = @()
foreach ($p in $all) {
  if (-not $found.ContainsKey([int]$p.ProcessId)) { continue }
  $out += [PSCustomObject]@{ pid = [int]$p.ProcessId; name = $p.Name }
}
@($out) | ConvertTo-Json -Depth 3
`;

/**
 * Kill what a PREVIOUS run of this agent left behind.
 *
 * Called once at startup. Returns what it cleaned and what it deliberately left alone,
 * because a reaper that reports nothing is indistinguishable from one that does nothing
 * - and this one runs unattended at every boot.
 *
 * @returns {Promise<{reaped: Array<{pid:number,name:string}>, skipped: number}>}
 */
export async function reapOrphans() {
  const state = readLedger();
  const pids = [...new Set(state.entries.map((entry) => Number(entry?.pid)).filter((n) => Number.isFinite(n) && n > 0))];
  // Start from a clean ledger: anything still alive after this pass is either gone now
  // or was never ours, and neither belongs in the next run's list.
  try {
    fs.writeFileSync(LEDGER_FILE, JSON.stringify({ entries: [] }, null, 2) + '\n', { mode: 0o600 });
  } catch {
    // If we cannot clear it, the worst case is re-examining the same pids next time.
  }
  if (pids.length === 0) return { reaped: [], skipped: 0 };
  // Windows only, and stated rather than silently degraded: the identification relies on
  // a full process list with parent links, which is how this is queried here.
  if (process.platform !== 'win32') {
    return { reaped: [], skipped: pids.length };
  }

  let found = [];
  try {
    found = await runJsonArray(LEFTOVER_SCRIPT(pids), { timeoutMs: 15000 });
  } catch {
    return { reaped: [], skipped: pids.length };
  }
  const reaped = [];
  for (const row of found) {
    const pid = Number(row?.pid);
    if (!Number.isFinite(pid) || pid <= 0) continue;
    killTree(pid);
    reaped.push({ pid, name: String(row?.name ?? '') });
  }
  return { reaped, skipped: Math.max(0, pids.length - reaped.length) };
}

/** One line for the startup banner. Null when there was nothing to say. */
export function describeReap({ reaped = [], skipped = 0 } = {}) {
  if (reaped.length === 0 && skipped === 0) return null;
  const parts = [];
  if (reaped.length > 0) {
    parts.push(`回收了上次遗留的 ${reaped.length} 个内核进程（${reaped.map((r) => r.name).join('、')}）`);
  }
  if (skipped > 0) {
    parts.push(`${skipped} 个 pid 已不存在或不属于本程序，未处理`);
  }
  return parts.join('；');
}
