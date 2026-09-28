/**
 * Privileged actions: terminating processes and controlling services.
 *
 * These can break the machine the user is relying on to work remotely, so every
 * action is guarded:
 *
 *   - a deny-list blocks processes whose termination would take down the host,
 *     the network path, or our own agent;
 *   - the action is re-validated here rather than trusting the client;
 *   - every attempt is reported back with a structured result, including
 *     refusals, so the phone shows what happened instead of failing silently.
 */
import { runJsonArray, runPowerShell } from './exec.js';

/**
 * Processes that must never be killed through this API.
 * Killing any of these can drop the network link or destabilise Windows, which
 * would defeat the entire point of remote access.
 *
 * Note: "node" is deliberately NOT here. This machine runs many Node services
 * the user legitimately wants to manage; only the agent's own process (and its
 * parent) are protected, by PID, in AGENT_PIDS below.
 */
const PROTECTED_PROCESS_NAMES = new Set([
  'system',
  'registry',
  'memory compression',
  'idle',
  'smss', 'csrss', 'wininit', 'winlogon', 'services', 'lsass',
  'svchost', 'fontdrvhost', 'dwm', 'explorer', 'sihost', 'ctfmon',
  'taskhostw', 'shellexperiencehost', 'startmenuexperiencehost',
  'searchhost', 'runtimebroker', 'dllhost', 'conhost', 'wudfhost',
  'spoolsv', 'audiodg', 'securityhealthservice', 'msmpeng',
  'tailscaled', // killing this severs the remote link
]);

/**
 * PIDs that must never be killed: the agent itself and whatever launched it.
 * Killing these drops the connection the user is working through.
 */
const AGENT_PIDS = new Set([process.pid, process.ppid].filter((n) => Number.isInteger(n) && n > 0));

/** Service names that must never be stopped through this API. */
const PROTECTED_SERVICES = new Set([
  'dnscache', 'dhcp', 'nsi', 'netprofm', 'nlasvc', 'windefend',
  'wuauserv', 'bits', 'lanmanworkstation', 'lanmanserver',
  'rpcss', 'rpceptmapper', 'eventlog', 'schedule', 'themes',
  'audioendpointbuilder', 'audiosrv', 'termservice',
  'tailscale',
]);

export function isProtectedProcessName(name) {
  return PROTECTED_PROCESS_NAMES.has(String(name ?? '').toLowerCase());
}

export function isProtectedService(name) {
  return PROTECTED_SERVICES.has(String(name ?? '').toLowerCase());
}

const ALLOWED_SERVICE_ACTIONS = new Set(['start', 'stop', 'restart']);

/**
 * Terminate a process by PID.
 * @returns {Promise<{ok: boolean, code: string, message: string}>}
 */
export async function killProcess(pid) {
  const id = Number(pid);
  if (!Number.isInteger(id) || id <= 0) {
    return { ok: false, code: 'bad_pid', message: `invalid pid: ${pid}` };
  }

  if (AGENT_PIDS.has(id)) {
    return {
      ok: false,
      code: 'protected',
      message: '这是 TermDesk 代理自身进程，结束它会立即断开连接',
    };
  }

  // Look the process up first so we can apply the protection list, which is
  // keyed by name rather than PID.
  let name;
  try {
    const rows = await runJsonArray(
      `@(Get-Process -Id ${id} -ErrorAction Stop | Select-Object -Property Id,ProcessName) | ConvertTo-Json -Depth 3`,
      { timeoutMs: 15000 },
    );
    if (rows.length === 0) {
      return { ok: false, code: 'not_found', message: `no process with pid ${id}` };
    }
    name = String(rows[0].ProcessName ?? '');
  } catch (err) {
    return { ok: false, code: 'not_found', message: `no process with pid ${id}` };
  }

  if (isProtectedProcessName(name)) {
    return {
      ok: false,
      code: 'protected',
      message: `"${name}" 受保护，拒绝结束（结束它会切断远程连接或影响系统稳定）`,
    };
  }

  const { stdout, stderr, timedOut } = await runPowerShell(
    `Stop-Process -Id ${id} -Force -ErrorAction Stop; 'killed'`,
    { timeoutMs: 15000 },
  );

  if (timedOut) {
    return { ok: false, code: 'timeout', message: '操作超时' };
  }
  if (stdout.includes('killed')) {
    return { ok: true, code: 'killed', message: `已结束 ${name} (pid ${id})` };
  }
  const detail = stderr.trim().split('\n')[0] ?? 'unknown error';
  return { ok: false, code: 'failed', message: `结束失败：${detail}` };
}

/**
 * Start, stop or restart a Windows service.
 * @returns {Promise<{ok: boolean, code: string, message: string}>}
 */
export async function controlService(name, action) {
  const service = String(name ?? '').trim();
  const verb = String(action ?? '').trim().toLowerCase();

  if (service.length === 0) {
    return { ok: false, code: 'bad_service', message: 'service name is required' };
  }
  if (!ALLOWED_SERVICE_ACTIONS.has(verb)) {
    return {
      ok: false,
      code: 'bad_action',
      message: `unsupported action "${action}"; expected start, stop or restart`,
    };
  }
  if (isProtectedService(service)) {
    return {
      ok: false,
      code: 'protected',
      message: `"${service}" 受保护，拒绝操作（停止它会切断远程连接或影响系统稳定）`,
    };
  }

  // Never interpolate the raw name into the script: resolve it through
  // Get-Service first and confirm it exists and is unambiguous.
  let resolved;
  try {
    const rows = await runJsonArray(
      `@(Get-Service -Name '${service.replace(/'/g, "''")}' -ErrorAction Stop | Select-Object -Property Name,Status) | ConvertTo-Json -Depth 3`,
      { timeoutMs: 15000 },
    );
    if (rows.length !== 1) {
      return { ok: false, code: 'not_found', message: `service "${service}" not found or ambiguous` };
    }
    resolved = rows[0];
  } catch {
    return { ok: false, code: 'not_found', message: `service "${service}" not found` };
  }

  const safeName = String(resolved.Name).replace(/'/g, "''");
  const script = verb === 'restart'
    ? `Restart-Service -Name '${safeName}' -Force -ErrorAction Stop; 'done'`
    : `${verb === 'start' ? 'Start' : 'Stop'}-Service -Name '${safeName}' -ErrorAction Stop; 'done'`;

  const { stdout, stderr, timedOut } = await runPowerShell(script, { timeoutMs: 45000 });

  if (timedOut) {
    return { ok: false, code: 'timeout', message: '操作超时（服务可能正在等待依赖）' };
  }
  if (stdout.includes('done')) {
    const zh = { start: '已启动', stop: '已停止', restart: '已重启' }[verb];
    return { ok: true, code: 'done', message: `${zh} ${resolved.Name}` };
  }

  const detail = stderr.trim().split('\n')[0] ?? 'unknown error';
  // Access-denied is by far the most common outcome, and the user needs to know
  // it is a permissions problem rather than a bug.
  const denied = /access is denied|拒绝访问|requires elevation|需要提升/i.test(stderr);
  return {
    ok: false,
    code: denied ? 'access_denied' : 'failed',
    message: denied
      ? '权限不足：该操作需要管理员权限'
      : `操作失败：${detail}`,
  };
}
