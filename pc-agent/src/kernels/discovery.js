/** File-system-only discovery helpers for commands and Node-hosted CLIs. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function pathDirectories(env = process.env) {
  return [...new Set(String(env.PATH ?? '').split(path.delimiter).map((item) => item.trim()).filter(Boolean))];
}

function isFile(file) {
  try { return fs.statSync(file).isFile(); } catch { return false; }
}

function hasNodeShebang(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const header = Buffer.alloc(256);
      const size = fs.readSync(fd, header, 0, header.length, 0);
      return /^#![^\r\n]*\bnode(?:\.exe)?(?:\s|$)/i.test(header.toString('utf8', 0, size).split(/\r?\n/, 1)[0] ?? '');
    } finally { fs.closeSync(fd); }
  } catch { return false; }
}

function hasPeHeader(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const header = Buffer.alloc(2);
    return fs.readSync(fd, header, 0, 2, 0) === 2 && header.toString('ascii') === 'MZ';
  } catch { return false; }
  finally { if (fd !== undefined) fs.closeSync(fd); }
}

/** Find an actual Windows executable by name; never return a shell shim. */
export function findPathExecutable(name, { env = process.env, platform = process.platform, rejected = [] } = {}) {
  for (const directory of pathDirectories(env)) {
    const candidates = platform === 'win32'
      ? [`${name}.exe`, `${name}.com`, name]
      : [name];
    for (const candidate of candidates) {
      const file = path.join(directory, candidate);
      if (!isFile(file)) continue;
      if (platform === 'win32' && !hasPeHeader(file)) {
        const extension = path.extname(file).toLowerCase();
        if (['.cmd', '.bat', '.ps1'].includes(extension)) {
          rejected.push({ path: file, reason: '脚本启动器不直接运行；仅接受经安装元数据验证的真实入口' });
        }
        continue;
      }
      return file;
    }
  }
  return null;
}

function npmPrefixes(env = process.env) {
  const prefixes = [];
  if (env.NPM_CONFIG_PREFIX) prefixes.push(env.NPM_CONFIG_PREFIX);
  for (const directory of pathDirectories(env)) {
    prefixes.push(directory);
    if (['bin', 'scripts'].includes(path.basename(directory).toLowerCase())) prefixes.push(path.dirname(directory));
  }
  if (env.APPDATA) prefixes.push(path.join(env.APPDATA, 'npm'));
  if (!env.APPDATA) prefixes.push(path.join(os.homedir(), 'AppData', 'Roaming', 'npm'));
  return [...new Set(prefixes.map((item) => path.resolve(item)))];
}

function packageBin(packageDir, command, expectedTarget = null, { nodeOnly = false } = {}) {
  let metadata;
  try { metadata = JSON.parse(fs.readFileSync(path.join(packageDir, 'package.json'), 'utf8')); } catch { return null; }
  const declared = typeof metadata.bin === 'string' ? metadata.bin : metadata.bin?.[command];
  if (typeof declared !== 'string' || !declared.trim()) return null;
  const target = path.resolve(packageDir, declared);
  const relative = path.relative(packageDir, target);
  if (!relative || relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) return null;
  if (expectedTarget && path.resolve(expectedTarget).toLowerCase() !== target.toLowerCase()) return null;
  if (!isFile(target)) return null;
  const extension = path.extname(target).toLowerCase();
  if (extension === '.cmd' || extension === '.bat' || extension === '.ps1') return null;
  if (['.js', '.mjs', '.cjs'].includes(extension) || (!extension && hasNodeShebang(target))) {
    return { bin: process.execPath, args: [target], script: target, source: 'npm-node' };
  }
  if (nodeOnly) return null;
  if (extension === '.exe' || hasPeHeader(target)) {
    return { bin: target, args: [], script: null, source: 'npm-native' };
  }
  return null;
}

function npmTargetFromWindowsShim(shim, { nodeOnly = false } = {}) {
  let source;
  try { source = fs.readFileSync(shim, 'utf8'); } catch { return null; }
  // npm-generated Windows launchers refer to the package's bin relative to the
  // prefix. Read that location only; wrapper contents are never evaluated.
  const match = source.match(/%dp0%[\\/]+node_modules[\\/]+([^"\r\n]+?)(?=["\s]|$)/i);
  if (!match) return null;
  const parts = match[1].split(/[\\/]+/).filter(Boolean);
  const scoped = parts[0]?.startsWith('@');
  const packageParts = parts.slice(0, scoped ? 2 : 1);
  if (packageParts.length !== (scoped ? 2 : 1) || parts.length <= packageParts.length) return null;
  const prefix = path.dirname(shim);
  const packageDir = path.join(prefix, 'node_modules', ...packageParts);
  const target = path.join(prefix, 'node_modules', ...parts);
  const command = path.basename(shim, path.extname(shim));
  return packageBin(packageDir, command, target, { nodeOnly });
}

/**
 * Resolve an npm-installed command through its generated shim and package bin
 * metadata, or through known package names beneath npm prefixes found on PATH.
 */
export function findNpmCommand(command, {
  env = process.env,
  platform = process.platform,
  packages = [],
  prefixes = [],
  rejected = [],
  nodeOnly = false,
} = {}) {
  if (platform !== 'win32') return null;
  for (const directory of pathDirectories(env)) {
    for (const extension of ['.cmd', '.ps1']) {
      const shim = path.join(directory, command + extension);
      if (!isFile(shim)) continue;
      const resolved = extension === '.cmd' ? npmTargetFromWindowsShim(shim, { nodeOnly }) : null;
      if (resolved) return resolved;
      rejected.push({ path: shim, reason: '检测到启动器，但没有匹配的 npm bin 元数据和可运行入口' });
    }
  }

  for (const prefix of [...new Set([...npmPrefixes(env), ...prefixes.map((item) => path.resolve(item))])]) {
    for (const name of packages) {
      const packageDir = path.join(prefix, 'node_modules', ...name.split('/'));
      const resolved = packageBin(packageDir, command, null, { nodeOnly });
      if (resolved) return resolved;
    }
  }
  return null;
}

/**
 * Locate the generated DSH PowerShell shim and extract its declared JS runtime
 * entry. This is static inspection only; the launcher itself is never run.
 */
export function dshScriptFromShim({ env = process.env, rejected = [] } = {}) {
  for (const directory of pathDirectories(env)) {
    const shim = path.join(directory, 'dsh.ps1');
    if (!isFile(shim)) continue;
    let source;
    try { source = fs.readFileSync(shim, 'utf8'); } catch { continue; }
    const match = source.match(/^\s*\$dshBin\s*=\s*(['"])(.+?)\1\s*$/im);
    if (!match) {
      rejected.push({ path: shim, reason: '找到 DSH 启动器，但没有可识别的 JS 入口声明' });
      continue;
    }
    const target = path.resolve(match[2]);
    if (!['.js', '.mjs', '.cjs'].includes(path.extname(target).toLowerCase())) {
      rejected.push({ path: shim, reason: 'DSH 启动器声明的入口不是 Node JS 脚本' });
      continue;
    }
    if (!isFile(target)) {
      rejected.push({ path: shim, reason: `找到 DSH 启动器，但目标运行时缺失：${target}` });
      continue;
    }
    return target;
  }
  return null;
}
