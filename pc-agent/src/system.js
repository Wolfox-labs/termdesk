/**
 * System metrics collection for TermDesk.
 *
 * Kept dependency-free on purpose: everything here comes from node:os / node:fs
 * so the agent runs on a bare Node install with no native modules.
 */
import os from 'node:os';
import fs from 'node:fs/promises';

/** Windows drive letters we probe for disk usage. */
const PROBE_DRIVES = (process.env.TERMDESK_DRIVES || 'C,D,E,F,G').split(',').map((s) => s.trim()).filter(Boolean);

let lastCpuSample = null;

function sampleCpuTimes() {
  return os.cpus().map((cpu) => {
    const t = cpu.times;
    const total = t.user + t.nice + t.sys + t.idle + t.irq;
    return { idle: t.idle, total };
  });
}

/**
 * CPU usage percentage since the previous call.
 * Returns null on the first call (no baseline yet) — the client should render
 * a placeholder rather than a bogus 0%.
 */
export function cpuUsage() {
  const now = sampleCpuTimes();
  if (lastCpuSample === null) {
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
      // Drive letter not present — expected, skip silently.
    }
  }
  return out;
}

export async function collectStatus() {
  return {
    hostname: os.hostname(),
    platform: `${os.platform()} ${os.release()}`,
    arch: os.arch(),
    uptimeSeconds: Math.floor(os.uptime()),
    cpu: {
      model: os.cpus()[0]?.model?.trim() ?? 'unknown',
      cores: os.cpus().length,
      usagePercent: cpuUsage(),
    },
    memory: memory(),
    disks: await disks(),
    loadavg: os.loadavg(),
  };
}
