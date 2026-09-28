/**
 * Process and service inventory for the Windows host.
 *
 * Both collectors shell out to Windows PowerShell 5.1 because the agent has no
 * native modules. Two real-world quirks are handled here:
 *
 *   1. Many system processes deny access to CPU time and start time, so those
 *      fields are frequently null. They must never be assumed present.
 *   2. ConvertTo-Json defaults to depth 2 and silently flattens deeper data,
 *      so every call passes an explicit -Depth.
 *
 * Process inventory is expensive (~1s, 40KB), so results are cached briefly and
 * repeated callers within the TTL share one collection.
 */
import { runJsonArray } from './exec.js';

const PROCESS_CACHE_MS = 4000;
const SERVICE_CACHE_MS = 15000;
const MAX_ITEMS = 400;

let processCache = { at: 0, value: null, inFlight: null };
let serviceCache = { at: 0, value: null, inFlight: null };

const PROCESS_SCRIPT = `
$items = Get-Process | ForEach-Object {
  $p = $_
  $start = $null
  try { $start = $p.StartTime.ToUniversalTime().ToString('o') } catch { }
  $cpu = $null
  try { if ($null -ne $p.CPU) { $cpu = [math]::Round([double]$p.CPU, 2) } } catch { }
  $threads = 0
  try { $threads = $p.Threads.Count } catch { }
  [PSCustomObject]@{
    pid        = $p.Id
    name       = $p.ProcessName
    cpuSeconds = $cpu
    memBytes   = $p.WorkingSet64
    startTime  = $start
    threads    = $threads
  }
}
@($items | Sort-Object -Property memBytes -Descending | Select-Object -First ${MAX_ITEMS}) | ConvertTo-Json -Depth 5
`;

const SERVICE_SCRIPT = `
$items = Get-Service | ForEach-Object {
  $s = $_
  $startType = $null
  try { $startType = $s.StartType.ToString() } catch { }
  [PSCustomObject]@{
    name        = $s.Name
    displayName = $s.DisplayName
    status      = $s.Status.ToString()
    startType   = $startType
    canStop     = [bool]$s.CanStop
  }
}
@($items) | ConvertTo-Json -Depth 5
`;

function normalizeProcess(row) {
  return {
    pid: Number(row.pid ?? 0),
    name: String(row.name ?? ''),
    // Null means "the OS would not tell us", which the UI shows as "—".
    cpuSeconds: row.cpuSeconds === null || row.cpuSeconds === undefined ? null : Number(row.cpuSeconds),
    memBytes: Number(row.memBytes ?? 0),
    startTime: row.startTime ?? null,
    threads: Number(row.threads ?? 0),
  };
}

function normalizeService(row) {
  return {
    name: String(row.name ?? ''),
    displayName: String(row.displayName ?? ''),
    status: String(row.status ?? 'Unknown'),
    startType: row.startType ?? null,
    canStop: Boolean(row.canStop),
  };
}

/** Process list, sorted by memory descending, capped at MAX_ITEMS. */
export async function listProcesses({ force = false } = {}) {
  const now = Date.now();
  if (!force && processCache.value && now - processCache.at < PROCESS_CACHE_MS) {
    return processCache.value;
  }
  // Collapse concurrent callers onto a single collection run.
  if (processCache.inFlight) return processCache.inFlight;

  const run = (async () => {
    try {
      const rows = await runJsonArray(PROCESS_SCRIPT, { timeoutMs: 30000 });
      const items = rows.map(normalizeProcess);
      const value = { capturedAt: new Date().toISOString(), items };
      processCache = { at: Date.now(), value, inFlight: null };
      return value;
    } catch (err) {
      processCache.inFlight = null;
      throw err;
    }
  })();

  processCache.inFlight = run;
  return run;
}

/** Service list. */
export async function listServices({ force = false } = {}) {
  const now = Date.now();
  if (!force && serviceCache.value && now - serviceCache.at < SERVICE_CACHE_MS) {
    return serviceCache.value;
  }
  if (serviceCache.inFlight) return serviceCache.inFlight;

  const run = (async () => {
    try {
      const rows = await runJsonArray(SERVICE_SCRIPT, { timeoutMs: 30000 });
      const items = rows.map(normalizeService);
      const value = { capturedAt: new Date().toISOString(), items };
      serviceCache = { at: Date.now(), value, inFlight: null };
      return value;
    } catch (err) {
      serviceCache.inFlight = null;
      throw err;
    }
  })();

  serviceCache.inFlight = run;
  return run;
}

/** Case-insensitive filter helper shared by the process and service paths. */
export function matchesQuery(haystack, query) {
  if (!query) return true;
  return haystack.toLowerCase().includes(query.toLowerCase());
}

/**
 * Drop cached inventory after a successful mutation so the next read reflects
 * reality instead of a stale snapshot.
 */
export function invalidateInventory() {
  processCache = { at: 0, value: null, inFlight: processCache.inFlight };
  serviceCache = { at: 0, value: null, inFlight: serviceCache.inFlight };
}
