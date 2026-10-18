/**
 * Where the DeepSeek Harness CLI really is, and how to start it.
 *
 * Measured on this machine (2026-10-18, DSH desktop 0.2.1-rc.1):
 *
 *   - `dsh.cmd` / `dsh.ps1` on PATH are generated shims whose declared entry is
 *     `%APPDATA%\io.github.hairyf.deepseek-harness-desktop\dependencies\dsh\...`;
 *     that directory does not exist after the app moved to
 *     `%LOCALAPPDATA%\Programs\DeepSeek Harness`. The launcher is broken, the
 *     runtime is installed.
 *   - The real entry is inside the app's archive:
 *       <install>\resources\app.asar\dsh\node_modules\@deepseek-ai\dsh\lib\bin.js
 *   - Plain `node.exe` cannot read inside an archive. Electron's bundled Node can,
 *     when the app binary is run with `ELECTRON_RUN_AS_NODE=1` — the same Node
 *     24.21.0 the desktop app itself runs, so the archive and its native
 *     dependencies load exactly as they do for the app.
 *
 * Everything here is discovery and argv: no install is modified, nothing is
 * copied out of the archive, and no user data is touched. A machine with a
 * standalone `dsh` (npm, or a launcher on PATH) keeps working — that candidate is
 * tried before the desktop install, because it is the user's own choice.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { asarArchiveOf, asarHas, fileExists, isAsarPath } from './asar.js';
import { dshScriptFromShim, findNpmCommand } from './discovery.js';

/** Relative location of the SDK CLI entry, both inside an archive and in a real tree. */
const DSH_ENTRY_SUFFIX = path.join('node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js');

function isFile(file) {
  try { return fs.statSync(file).isFile(); } catch { return false; }
}

/** Where the desktop application may be installed. */
export function desktopInstallDirs(env = process.env, homedir = os.homedir()) {
  const dirs = [];
  const localAppData = env.LOCALAPPDATA || path.join(homedir, 'AppData', 'Local');
  dirs.push(path.join(localAppData, 'Programs', 'DeepSeek Harness'));
  dirs.push(path.join(localAppData, 'Programs', 'DeepSeek Harness (dev)'));
  for (const base of [env.ProgramFiles, env['ProgramFiles(x86)'], 'C:\\Program Files']) {
    if (typeof base === 'string' && base.length > 0) dirs.push(path.join(base, 'DeepSeek Harness'));
  }
  return [...new Set(dirs)];
}

/** The Electron host that can read inside this archive (the app next to it). */
function electronHostFor(archive, env = process.env) {
  if (env.TERMDESK_DSH_NODE && isFile(env.TERMDESK_DSH_NODE)) return env.TERMDESK_DSH_NODE;
  // <install>\resources\app.asar → <install>\DeepSeek Harness.exe
  const resources = path.dirname(archive);
  const install = path.dirname(resources);
  for (const name of ['DeepSeek Harness.exe', 'deepseek-harness.exe']) {
    const candidate = path.join(install, name);
    if (isFile(candidate)) return candidate;
  }
  return null;
}

/**
 * `{ command, prefixArgs, script, env, source }` for one entry.
 *
 * `prefixArgs` is what goes before the profile flags; it stays empty today but is
 * part of the shape so a wrapper that needs an argument before the script does not
 * force every caller to learn a second spawn contract.
 */
function scriptSpec(script, env = process.env) {
  if (isAsarPath(script)) {
    const archive = asarArchiveOf(script);
    const host = electronHostFor(archive, env);
    if (!host) return null;
    return {
      source: 'desktop-app',
      command: host,
      prefixArgs: [],
      script,
      env: { ELECTRON_RUN_AS_NODE: '1' },
    };
  }
  if (!isFile(script)) return null;
  return { source: 'node-script', command: process.execPath, prefixArgs: [], script, env: {} };
}

/** A DSH launcher that is its own executable (a standalone install or a test stand-in). */
function executableSpec(file, source) {
  if (!isFile(file)) return null;
  return { source, command: file, prefixArgs: [], script: null, env: {} };
}

function desktopSpec(dir, env = process.env) {
  const resourceDir = path.join(dir, 'resources');
  const archive = path.join(resourceDir, 'app.asar');
  if (!isFile(archive)) return null;
  const script = path.join(archive, 'dsh', DSH_ENTRY_SUFFIX);
  if (!asarHas(archive, script)) return null;
  const spec = scriptSpec(script, env);
  if (spec) spec.appDir = dir;
  return spec;
}

/**
 * Resolve the DSH runtime, in the order a person would expect it to be resolved:
 *
 *   1. `TERMDESK_DSH` — an explicit answer wins over every guess;
 *   2. a standalone `dsh` the machine already has (its generated shim, then npm
 *      metadata) — the user's own install is not second-guessed;
 *   3. the desktop application's bundled CLI, including inside its archive;
 *   4. the old Roaming `dependencies/dsh` tree, for an app that still ships that way.
 *
 * @returns {{source:string, command:string, prefixArgs:string[], script:string|null, env:object}|null}
 */
export function resolveDshRuntime({
  env = process.env,
  homedir = os.homedir(),
  platform = process.platform,
  rejected = [],
} = {}) {
  const explicit = env.TERMDESK_DSH;
  if (explicit) {
    const fromExplicit = /\.[cm]?js$/i.test(explicit) || isAsarPath(explicit)
      ? scriptSpec(explicit, env)
      : executableSpec(explicit, 'explicit');
    if (fromExplicit) return fromExplicit;
    rejected.push({ path: explicit, reason: 'TERMDESK_DSH 指向的入口不存在或不可读' });
  }

  if (platform !== 'win32') {
    // Unix installs expose `dsh` as an executable launcher on PATH.
    for (const dir of String(env.PATH ?? '').split(path.delimiter).filter(Boolean)) {
      const candidate = path.join(dir, 'dsh');
      if (isFile(candidate)) return executableSpec(candidate, 'path');
    }
    return null;
  }

  // The launcher the desktop app generated: static inspection only, never run.
  const shimTarget = dshScriptFromShim({ env, rejected });
  if (shimTarget) {
    const spec = scriptSpec(shimTarget, env);
    if (spec) return spec;
  }

  const npm = findNpmCommand('dsh', { env, platform, packages: ['@deepseek-ai/dsh'], nodeOnly: true, rejected });
  if (npm?.script) {
    const spec = scriptSpec(npm.script, env);
    if (spec) return spec;
  }

  for (const dir of desktopInstallDirs(env, homedir)) {
    const spec = desktopSpec(dir, env);
    if (spec) return spec;
  }

  // The pre-move layout: a real tree under the app's Roaming data directory.
  const roaming = path.join(
    env.APPDATA || path.join(homedir, 'AppData', 'Roaming'),
    'io.github.hairyf.deepseek-harness-desktop',
    'dependencies',
    'dsh',
    DSH_ENTRY_SUFFIX,
  );
  const legacy = scriptSpec(roaming, env);
  if (legacy) return legacy;

  return null;
}

/** The argv to boot a profile through this runtime, script first. */
export function launchArgs(runtime, { profile = 'sdk', patch = null } = {}) {
  const args = [...(runtime?.prefixArgs ?? [])];
  if (runtime?.script) args.push(runtime.script);
  args.push('--profile', profile);
  if (patch) args.push('--patch', patch);
  return args;
}

/** The environment the runtime needs on top of the inherited one. */
export function launchEnv(runtime, base = process.env) {
  return { ...base, ...(runtime?.env ?? {}) };
}

/** What a console should show as this kernel's location. */
export function dshDisplayPath(runtime) {
  return runtime ? (runtime.script ?? runtime.command) : null;
}

export { DSH_ENTRY_SUFFIX, fileExists };
