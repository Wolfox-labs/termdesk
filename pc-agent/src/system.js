/**
 * System metrics collection for TermDesk.
 *
 * Two very different hosts serve this module, and the difference is not
 * cosmetic:
 *
 *   Windows PC      os.cpus() / os.totalmem() / one statfs per drive letter
 *   Android sandbox /proc/stat is denied to an app, so os.cpus() comes back
 *                   EMPTY and /proc/cpuinfo is denied too. Measured on a
 *                   vivo V2548A (Android 16): cpu model "unknown", cores 0,
 *                   usage null. What IS readable is /proc/self/*, /proc/meminfo,
 *                   /proc/<pid>/stat for the app's own uid, and
 *                   /sys/devices/system/cpu/present.
 *
 * So on a phone this reports the SANDBOX's own usage - the process tree rooted at
 * this agent - rather than the whole phone's. That is also the more useful
 * number: "how much is the sandbox eating" is the question a user actually has,
 * and the process tree is the only thing an app is allowed to see anyway.
 *
 * Everything here stays dependency-free (node:os / node:fs) so the agent runs on
 * a bare Node install with no native modules.
 */
import os from 'node:os';
import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';

const IS_WINDOWS = process.platform === 'win32';

/** Windows drive letters we probe for disk usage. */
const PROBE_DRIVES = (process.env.TERMDESK_DRIVES || 'C,D,E,F,G').split(',').map((s) => s.trim()).filter(Boolean);

/**
 * Linux/Android paths whose filesystem usage is worth reporting. Defaults to
 * the sandbox home, which is the filesystem the user is actually filling with
 * their work; the OS partition is not their business.
 */
const PROBE_PATHS = (process.env.TERMDESK_DISK_PATHS || os.homedir())
  .split(path.delimiter)
  .map((s) => s.trim())
  .filter(Boolean);

/** USER_HZ. 100 on Linux and Android on every ABI we care about. */
const CLK_TCK = 100;

/** Assume 4 KiB pages when all we have is /proc/<pid>/stat's rss field. */
const PAGE_SIZE = 4096;

let lastCpuSample = null;
let lastTreeSample = null;

function sampleCpuTimes() {
  return os.cpus().map((cpu) => {
    const t = cpu.times;
    const total = t.user + t.nice + t.sys + t.idle + t.irq;
    return { idle: t.idle, total };
  });
}

/**
 * CPU usage percentage since the previous call.
 *
 * On a PC this is the whole machine. On Android, where os.cpus() is empty, it is
 * the sandbox process tree instead - see the note at the top.
 * Returns null on the first call (no baseline yet) - the client should render a
 * placeholder rather than a bogus 0%.
 */
export function cpuUsage() {
  const now = sampleCpuTimes();
  if (now.length === 0) return null; // Android: no system-wide view. See collectStatus.
  if (lastCpuSample === null || lastCpuSample.length !== now.length) {
    lastCpuSample = now;
    return null;
  }
  let idleDelta = 0;
  let totalDelta = 0;
  for (let i = 0; i < now.length; i += 1) {
    idleDelta += now[i].idle - lastCpuSample[i].idle;
    totalDelta += now[i].total - lastCpuSample[i].total;
  }
  lastCpuSample = now;
  if (totalDelta <= 0) return null;
  const used = (1 - idleDelta / totalDelta) * 100;
  return Math.max(0, Math.min(100, Number(used.toFixed(1))));
}

/** Number of CPUs, falling back to /sys when os.cpus() is denied. */
export function cpuCores() {
  const fromOs = os.cpus().length;
  if (fromOs > 0) return fromOs;
  try {
    // "0-7" (or "0", or "0-3,4-7").
    const present = fsSync.readFileSync('/sys/devices/system/cpu/present', 'utf8').trim();
    let n = 0;
    for (const part of present.split(',')) {
      const m = /^(\d+)(?:-(\d+))?$/.exec(part.trim());
      if (m) n += m[2] ? Number(m[2]) - Number(m[1]) + 1 : 1;
    }
    return n;
  } catch {
    return 0;
  }
}

/**
 * One /proc/<pid>/stat, parsed defensively.
 *
 * comm sits in parentheses and may itself contain spaces or parentheses, so the
 * split starts after the LAST ')'. Field numbers are 1-based in proc(5); after
 * the comm is stripped, field N lives at rest[N - 3].
 */
function readProcStat(pid) {
  const text = fsSync.readFileSync(`/proc/${pid}/stat`, 'utf8');
  const open = text.indexOf('(');
  const close = text.lastIndexOf(')');
  if (open < 0 || close < 0) return null;
  const rest = text.slice(close + 2).trim().split(/\s+/);
  return {
    pid: Number(text.slice(0, open).trim()),
    comm: text.slice(open + 1, close),
    ppid: Number(rest[1]),      // field 4
    utime: Number(rest[11]),    // field 14
    stime: Number(rest[12]),    // field 15
    rssPages: Number(rest[21]), // field 24
  };
}

/** Every process in this agent's tree: itself plus all descendants. */
function processTree(rootPid = process.pid) {
  let names;
  try {
    names = fsSync.readdirSync('/proc');
  } catch {
    return [];
  }
  const byPid = new Map();
  for (const name of names) {
    if (!/^\d+$/.test(name)) continue;
    try {
      const s = readProcStat(Number(name));
      if (s && Number.isFinite(s.pid)) byPid.set(s.pid, s);
    } catch {
      // Another uid's process, or one that exited mid-scan. Not ours to read.
    }
  }
  const tree = new Set([rootPid]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const s of byPid.values()) {
      if (!tree.has(s.pid) && tree.has(s.ppid)) {
        tree.add(s.pid);
        grew = true;
      }
    }
  }
  return [...tree].map((pid) => byPid.get(pid)).filter(Boolean);
}

/**
 * The sandbox's own footprint: CPU% of ONE core, RSS in bytes, process count.
 *
 * Reported as a rate against wall time, so one busy core reads 100% and a
 * multi-threaded burst can read above it - the same convention `top` uses.
 */
export function sandboxTree() {
  const tree = processTree();
  if (tree.length === 0) return { cpuPercent: null, rssBytes: 0, processCount: 0 };

  const ticks = tree.reduce((sum, s) => sum + s.utime + s.stime, 0);
  const rssBytes = tree.reduce((sum, s) => sum + Math.max(0, s.rssPages) * PAGE_SIZE, 0);
  const now = Date.now();

  let cpuPercent = null;
  if (lastTreeSample && now > lastTreeSample.at) {
    const seconds = (now - lastTreeSample.at) / 1000;
    const deltaTicks = ticks - lastTreeSample.ticks;
    if (seconds > 0 && deltaTicks >= 0) {
      cpuPercent = Number(((deltaTicks / CLK_TCK) / seconds * 100).toFixed(1));
    }
  }
  lastTreeSample = { at: now, ticks };

  return { cpuPercent, rssBytes, processCount: tree.length };
}

function memory() {
  const total = os.totalmem();
  const free = os.freemem();
  const used = total - free;
  return {
    totalBytes: total,
    usedBytes: used,
    freeBytes: free,
    usedPercent: Number(((used / total) * 100).toFixed(1)),
  };
}

async function disks() {
  if (IS_WINDOWS) {
    const out = [];
    for (const letter of PROBE_DRIVES) {
      const root = `${letter}:\\`;
      try {
        const st = await fs.statfs(root);
        const total = st.bsize * st.blocks;
        const free = st.bsize * st.bavail;
        if (total <= 0) continue;
        out.push({
          root,
          totalBytes: total,
          freeBytes: free,
          usedBytes: total - free,
          usedPercent: Number((((total - free) / total) * 100).toFixed(1)),
        });
      } catch {
        // Drive letter not present - expected, skip silently.
      }
    }
    return out;
  }

  // Linux / Android. The sandbox home is the interesting filesystem; the OS
  // partition is not something the user can fill.
  const out = [];
  for (const root of PROBE_PATHS) {
    try {
      const st = await fs.statfs(root);
      const total = st.bsize * st.blocks;
      const free = st.bsize * st.bavail;
      if (total <= 0) continue;
      out.push({
        root,
        totalBytes: total,
        freeBytes: free,
        usedBytes: total - free,
        usedPercent: Number((((total - free) / total) * 100).toFixed(1)),
      });
    } catch {
      // Path missing or unreadable - skip rather than fail the whole status.
    }
  }
  return out;
}

export async function collectStatus() {
  // Exactly ONE tree sample per collection. Sampling twice in a row measures a
  // sub-millisecond interval and reports nonsense - measured on an idle sandbox:
  // 148% on the first reading, where the first reading should have been null.
  const tree = IS_WINDOWS ? null : sandboxTree();
  return {
    hostname: os.hostname(),
    platform: `${os.platform()} ${os.release()}`,
    arch: os.arch(),
    uptimeSeconds: Math.floor(os.uptime()),
    cpu: {
      model: os.cpus()[0]?.model?.trim() ?? (IS_WINDOWS ? 'unknown' : `sandbox on ${os.arch()}`),
      cores: cpuCores(),
      // Windows: the machine's own CPU. Android: the sandbox tree's, because
      // the machine's is not readable by an app (see the note at the top).
      usagePercent: IS_WINDOWS ? cpuUsage() : tree.cpuPercent,
    },
    memory: memory(),
    disks: await disks(),
    loadavg: os.loadavg(),
    // Present only where the host IS the sandbox: the phone. On a PC this is
    // null, because there the interesting numbers are the machine's own.
    sandbox: tree ? { ...tree, coreCount: cpuCores() } : null,
  };
}