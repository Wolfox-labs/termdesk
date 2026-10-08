/**
 * What is running on this machine that this agent did not start.
 *
 * Why this exists, in the user's words: "the phone should show what is actually
 * running on the computer". Until now a conversation only appeared on the phone
 * after THIS agent created it, so an agent the person started in their own
 * terminal was invisible — they found out it existed only by trying to attach to
 * its session and being told (by the kernel) that another writer held it.
 *
 * The process list was already there (`inventory.js` enumerates every process);
 * what was missing was matching those processes back to the kernels TermDesk
 * knows about, and saying so over the wire.
 *
 * The honesty this file has to keep straight:
 *
 *   - A match is a process NAME match. Two builds of the same tool, or a wrapper
 *     script around it, look the same here. So a row is labelled as "an agent
 *     process", never as "your conversation X".
 *   - The agent cannot attach to these. It did not start them and holds no handle
 *     to their stdio, so there is nothing to open. `attachable: false` is part of
 *     the record so the UI cannot offer an action that would fail.
 *   - Conversations this agent DOES hold are excluded: they are already listed as
 *     live chats, and counting them twice would double every row the phone shows.
 */

/**
 * Executable names per kernel id.
 *
 * The kernel registry is the source of truth for what is installed and how it is
 * launched (`listKernels()`), so this list only maps a kernel to the process name
 * a launcher actually produces. It is spelled out rather than derived because the
 * derivation is platform-shaped: on Windows the process name is the executable's
 * basename without `.exe`, while on POSIX it is the basename as-is, and a shim
 * kernel is launched as `node <script>` so its process name is `node` — matching
 * that would report every Node process on the machine as an agent.
 */
const EXECUTABLE_NAMES = {
  codex: ['codex'],
  dsh: ['dsh'],
  opencode: ['opencode'],
  mimo: ['mimo'],
  'command-code': ['command-code', 'commandcode'],
  qoder: ['qoderclicn', 'qoder'],
};

/** The process name a kernel shows up as, or null when we cannot say. */
export function executableFor(kernelId) {
  const names = EXECUTABLE_NAMES[kernelId];
  if (!Array.isArray(names) || names.length === 0) return null;
  return names[0];
}

/** Strip a path down to the process name a platform would report. */
export function processNameOf(executablePath) {
  if (typeof executablePath !== 'string' || executablePath.length === 0) return null;
  const base = executablePath.split(/[\\/]/).pop() ?? '';
  if (!base) return null;
  return base.toLowerCase().endsWith('.exe') ? base.slice(0, -4) : base;
}

/**
 * Agent processes running on this machine that this agent does not own.
 *
 * @param {Array<{pid: number, name: string, memBytes?: number, cpuSeconds?: number}>} processes
 *   the machine's process list, as `inventory.js` produces it
 * @param {Array<{id: string, label: string, available?: boolean}>} kernels
 *   resolved kernels, as `listKernels()` produces them
 * @param {Set<string>} [knownPids] process ids this agent started, which are
 *   already visible as live conversations and must not be listed twice
 * @returns {Array<{kernelId: string, label: string, pid: number, name: string,
 *                  memBytes: number, cpuSeconds: number|null, attachable: false}>}
 */
export function externalKernelRuns(processes, kernels, knownPids = new Set()) {
  const byName = new Map();
  for (const kernel of kernels ?? []) {
    if (!kernel?.id) continue;
    // Only kernels that could actually be launched here: an entry we cannot
    // resolve is one whose binary we have never seen, so matching its name would
    // be guessing.
    if (kernel.available === false) continue;
    const exe = executableFor(kernel.id);
    if (!exe) continue;
    byName.set(exe.toLowerCase(), { id: kernel.id, label: kernel.label ?? kernel.id });
  }
  if (byName.size === 0) return [];

  const runs = [];
  for (const proc of processes ?? []) {
    const name = processNameOf(proc?.name);
    if (!name) continue;
    const kernel = byName.get(name.toLowerCase());
    if (!kernel) continue;
    const pid = Number(proc.pid);
    if (!Number.isFinite(pid) || pid <= 0) continue;
    if (knownPids.has(pid)) continue;
    runs.push({
      kernelId: kernel.id,
      label: kernel.label,
      pid,
      name: proc.name,
      memBytes: Number(proc.memBytes ?? 0),
      cpuSeconds: proc.cpuSeconds ?? null,
      // Stated in the record, not left for the UI to work out: we hold no handle
      // to this process, so there is nothing to attach to and nothing to type in.
      attachable: false,
    });
  }
  runs.sort((a, b) => b.memBytes - a.memBytes);
  return runs;
}

/** One line summarising the list, for a startup banner or a `status` frame. */
export function describeExternalRuns(runs) {
  if (!Array.isArray(runs) || runs.length === 0) return '没有检测到本机直接运行的 agent 进程';
  const byKernel = new Map();
  for (const run of runs) {
    byKernel.set(run.label, (byKernel.get(run.label) ?? 0) + 1);
  }
  return [...byKernel.entries()].map(([label, n]) => `${label}×${n}`).join('、');
}
