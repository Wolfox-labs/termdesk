/**
 * Thin wrapper around spawning PowerShell and getting JSON back.
 *
 * The agent shells out rather than using native modules so it stays
 * dependency-light and portable. Two things matter here:
 *   - output must be decoded as UTF-8, or non-ASCII process/service names
 *     come back as mojibake;
 *   - every call is bounded by a timeout, because a hung child process would
 *     otherwise wedge the whole agent.
 */
import { spawn } from 'node:child_process';

let cachedShell = null;

/** Prefer PowerShell 7 (UTF-8 native), fall back to Windows PowerShell. */
export function resolveShell() {
  if (cachedShell !== null) return cachedShell;
  cachedShell = process.env.TERMDESK_POWERSHELL || 'powershell.exe';
  return cachedShell;
}

export function setShell(exe) {
  cachedShell = exe;
}

/**
 * Run a PowerShell script and resolve with raw stdout text.
 * @returns {Promise<{stdout: string, stderr: string, code: number|null, timedOut: boolean}>}
 */
export function runPowerShell(script, { timeoutMs = 20000, maxBuffer = 24 * 1024 * 1024 } = {}) {
  return new Promise((resolve, reject) => {
    // Force UTF-8 on the way out so Chinese names survive the pipe.
    const preamble = '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); ';
    const args = [
      '-NoProfile',
      '-NonInteractive',
      '-ExecutionPolicy', 'Bypass',
      '-Command',
      preamble + script,
    ];

    let child;
    try {
      child = spawn(resolveShell(), args, {
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (err) {
      reject(err);
      return;
    }

    let stdout = '';
    let stderr = '';
    let timedOut = false;
    let settled = false;

    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill(); } catch { /* already gone */ }
    }, timeoutMs);

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
      if (stdout.length > maxBuffer) {
        try { child.kill(); } catch { /* already gone */ }
      }
    });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString('utf8'); });

    child.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });

    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ stdout, stderr, code, timedOut });
    });
  });
}

/**
 * Run a script that ends in `ConvertTo-Json` and parse the result.
 * Always resolves to an array so callers never special-case single results.
 */
export async function runJsonArray(script, options) {
  const { stdout, stderr, timedOut } = await runPowerShell(script, options);
  if (timedOut) throw new Error('PowerShell command timed out');
  const text = stdout.trim();
  if (text.length === 0) {
    if (stderr.trim().length > 0) throw new Error(stderr.trim().split('\n')[0]);
    return [];
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error(`could not parse command output as JSON: ${text.slice(0, 200)}`);
  }
  if (parsed === null) return [];
  return Array.isArray(parsed) ? parsed : [parsed];
}

/** Run a script for its side effect; reject with stderr on failure. */
export async function runJsonObject(script, options) {
  const rows = await runJsonArray(script, options);
  return rows[0] ?? null;
}
