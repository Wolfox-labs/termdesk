/**
 * Agent processes that run INSIDE a desktop app, and are therefore invisible to a
 * process-name match.
 *
 * Why this exists: the phone showed "还没有进行中的对话" while a DeepSeek Harness
 * conversation was running on the machine. The desktop build does not run as `dsh`
 * – it runs as `DeepSeek Harness.exe` with one child per session, each executing
 * `<app>/resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner.js`.
 * Matching process NAMES (which `kernelruns.js` did, and which is right for a CLI) can
 * therefore never see it, however well it works for `mimo` and `codex`.
 *
 * Two facts had to be measured on a real machine rather than guessed:
 *
 *   1. ONE conversation is MANY processes. The same session had a desktop host, five
 *      runners and three nested hosts sharing a single process tree. Reporting each
 *      would say "5 conversations" about one, which is worse than saying nothing.
 *   2. The top of that tree is the app's own executable, so the instance is reported
 *      once, by its root, with the tree's memory summed.
 *
 * The command line is the only reliable signal here, and the cost was measured: a
 * full `Win32_Process` query is ~600 ms on a 354-process machine, which is why this
 * is its own cached query rather than a field added to the process inventory that
 * every `procs.list` would then pay for.
 */
import { runJsonArray } from './exec.js';

/** How long a detected instance is reused. The UI refreshes on a 2 s cadence. */
const DETECT_CACHE_MS = 15000;

/**
 * How a desktop-launched agent is recognised.
 *
 * `executables` is the same signal the name-based path uses. `tokens` are matched
 * against the command line, and one token is enough: a process is part of the family
 * if EITHER says so, because a launcher may rename its executable or launch through a
 * runtime, and the arguments survive both.
 */
export const AGENT_FAMILIES = [
  {
    kernelId: 'dsh',
    label: 'DeepSeek Harness',
    executables: ['deepseek harness'],
    tokens: ['dsh-subprocess-local', 'dsh-desktop-host'],
  },
];

const PROCESS_SCRIPT = `
$items = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | ForEach-Object {
  [PSCustomObject]@{
    pid       = $_.ProcessId
    name      = $_.Name
    parentPid = $_.ParentProcessId
    memBytes  = $_.WorkingSetSize
    command   = $_.CommandLine
  }
}
@($items) | ConvertTo-Json -Depth 3
`;

/**
 * Does this process belong to one of the desktop agent families?
 *
 * Exported so the rule is testable without a machine: the classification is the part
 * that has to be right, and it is pure.
 */
export function familyOf(proc, families = AGENT_FAMILIES) {
  if (!proc || typeof proc !== 'object') return null;
  const name = String(proc.name ?? '').toLowerCase().replace(/\.exe$/, '');
  const command = String(proc.command ?? '').toLowerCase();
  for (const family of families) {
    if (family.executables.some((exe) => name === String(exe).toLowerCase())) return family;
    // A token match needs an actual command line: an absent one must not match, or
    // every process without a readable command line would be claimed by the first
    // family whose token list is consulted.
    if (command.length > 0 && family.tokens.some((token) => command.includes(String(token).toLowerCase()))) {
      return family;
    }
  }
  return null;
}

/**
 * Collapse a process list into one record per RUNNING INSTANCE.
 *
 * One instance = one family and one root, where the root is the member whose parent is
 * not itself in the list. Children contribute their memory to the root and nothing
 * else, which is what turns "5 processes" into "1 conversation" without hiding how
 * much of the machine it is holding.
 *
 * @returns {Array<{kernelId, label, pid, name, memBytes, processCount, cpuSeconds, attachable}>}
 */
export function groupIntoInstances(processes, families = AGENT_FAMILIES) {
  const members = [];
  for (const proc of processes ?? []) {
    const family = familyOf(proc, families);
    if (!family) continue;
    const pid = Number(proc.pid);
    if (!Number.isFinite(pid) || pid <= 0) continue;
    members.push({
      family,
      pid,
      parentPid: Number(proc.parentPid ?? 0),
      name: String(proc.name ?? ''),
      memBytes: Number(proc.memBytes ?? 0) || 0,
      cpuSeconds: proc.cpuSeconds ?? null,
    });
  }
  if (members.length === 0) return [];

  const byPid = new Map(members.map((m) => [m.pid, m]));
  // The root of a member is the outermost ancestor that is still a member. A cycle
  // cannot happen in a process tree, but the guard costs nothing and a wrong answer
  // here would hang the phone's refresh loop.
  const rootOf = (member) => {
    let current = member;
    const seen = new Set([member.pid]);
    while (byPid.has(current.parentPid) && !seen.has(current.parentPid)) {
      seen.add(current.parentPid);
      current = byPid.get(current.parentPid);
    }
    return current;
  };

  const instances = new Map();
  for (const member of members) {
    const root = rootOf(member);
    const key = `${member.family.kernelId}:${root.pid}`;
    const existing = instances.get(key);
    if (existing) {
      // Every member adds its own memory exactly once (the first member of a group
      // creates the entry with zero, so there is no double count of the root).
      existing.memBytes += member.memBytes;
      existing.processCount += 1;
      continue;
    }
    instances.set(key, {
      kernelId: member.family.kernelId,
      label: member.family.label,
      // The pid of the PROCESS the person can find in their task manager, not of
      // whichever child happened to be enumerated first.
      pid: root.pid,
      name: root.name,
      memBytes: 0,
      processCount: 0,
      cpuSeconds: member.cpuSeconds ?? null,
      attachable: false,
    });
    const created = instances.get(key);
    created.memBytes += member.memBytes;
    created.processCount += 1;
  }
  return [...instances.values()].sort((a, b) => b.memBytes - a.memBytes);
}

/** One cache, because the query costs ~600 ms and the UI polls. */
let detectCache = { at: 0, value: null, inFlight: null };

/**
 * Running instances of desktop-launched agents, or an empty list.
 *
 * Never throws: this feeds a status panel, and an unavailable process list must show
 * as "nothing detected" rather than as an error that blanks the screen.
 */
export async function detectDesktopAgentInstances({ force = false, families = AGENT_FAMILIES } = {}) {
  const now = Date.now();
  if (!force && detectCache.value && now - detectCache.at < DETECT_CACHE_MS) return detectCache.value;
  if (detectCache.inFlight) return detectCache.inFlight;

  const run = (async () => {
    try {
      const rows = await runJsonArray(PROCESS_SCRIPT, { timeoutMs: 20000 });
      const value = groupIntoInstances(rows.map((row) => ({
        pid: row.pid,
        name: row.name,
        parentPid: row.parentPid,
        memBytes: row.memBytes,
        command: row.command,
      })), families);
      detectCache = { at: Date.now(), value, inFlight: null };
      return value;
    } catch {
      // A failed detection is not an error the person can act on; the panel simply
      // keeps showing what it had.
      detectCache = { at: Date.now(), value: [], inFlight: null };
      return [];
    }
  })();

  detectCache.inFlight = run;
  return run;
}
