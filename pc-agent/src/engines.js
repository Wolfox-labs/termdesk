/**
 * Launch helpers shared by the chat pipeline and the Codex adapter.
 *
 * What used to live here: a one-shot "task" pipeline (`ai.submit` / `ai.tasks` /
 * `ai.cancel` / `ai.reset`) with its own probe of Codex and DSH. It was kept for
 * wire compatibility long after the product moved to `chat.*`, and it carried
 * its own idea of which kernels existed — so the phone's picker could disagree
 * with what a conversation would actually run on. It is gone now: the kernel
 * table lives in `kernels/registry.js`, conversations live in `chat.js`, and
 * this file only holds the process-level pieces both of them need.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';

/** Resolve the Codex binary: env override, then the known install location. */
export function findCodex() {
  if (process.env.TERMDESK_CODEX) return process.env.TERMDESK_CODEX;
  const base = path.join(os.homedir(), 'AppData', 'Local', 'OpenAI', 'Codex', 'bin');
  try {
    // bin/<hash>/codex.exe — pick any hash directory that has the binary.
    for (const entry of fs.readdirSync(base)) {
      const candidate = path.join(base, entry, 'codex.exe');
      if (fs.existsSync(candidate)) return candidate;
    }
  } catch {
    // fall through to the PATH name
  }
  return 'codex';
}

/** Resolve the DSH launcher entry script. */
export function findDsh() {
  if (process.env.TERMDESK_DSH) return process.env.TERMDESK_DSH;
  return path.join(
    os.homedir(),
    'AppData',
    'Roaming',
    'io.github.hairyf.deepseek-harness-desktop',
    'dependencies',
    'dsh',
    'node_modules',
    '@deepseek-ai',
    'dsh',
    'lib',
    'bin.js',
  );
}

/**
 * Build the `codex exec` argv for one turn.
 *
 * Kept because the Codex adapter and its tests share it: a fresh turn runs
 * `exec`, a follow-up runs `exec resume <thread_id>` and Codex itself carries
 * the history — the transcript is never re-fed into a new process.
 *
 * @param {object} options
 * @param {string} options.prompt
 * @param {string} options.cwd
 * @param {string|null} [options.resumeThreadId] thread id from `thread.started`
 * @param {string|null} [options.provider] optional model_provider override
 * @param {string|null} [options.model] optional model override
 */
export function buildCodexExecArgs({ prompt, cwd, resumeThreadId = null, provider = null, model = null }) {
  const useResume = Boolean(resumeThreadId);
  const args = useResume
    ? ['exec', 'resume', resumeThreadId, '--json', '--skip-git-repo-check']
    : ['exec', '--json', '--skip-git-repo-check', '-C', cwd];
  // Codex accepts -c key=value config overrides; provider/model ride along
  // without rewriting config.toml (codexconfig.js still owns persistent setup).
  if (provider) args.push('-c', `model_provider=${provider}`);
  if (model) args.push('-c', `model=${model}`);
  args.push(prompt);
  return args;
}

/**
 * Kill a child and its descendants. A coding agent spawns shells, so killing
 * only the direct child would leave orphans running. Shared with chat.js so
 * both pipelines cancel the same way.
 */
export function killProcessTree(child) {
  if (!child || child.killed) return;
  try {
    if (process.platform === 'win32' && child.pid) {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
        windowsHide: true,
        stdio: 'ignore',
      });
    } else {
      child.kill('SIGKILL');
    }
  } catch {
    try { child.kill(); } catch { /* already gone */ }
  }
}