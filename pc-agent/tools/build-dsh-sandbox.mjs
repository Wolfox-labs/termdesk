/**
 * Build the DSH tree that runs inside the phone's sandbox.
 *
 * The problem this solves is not "install DSH" - it is that a tree installed for
 * the wrong platform cannot even boot on the phone, and the failure looks like a
 * broken kernel rather than a wrong build. Measured, on the device:
 *
 *   1. `node-pty` ships prebuilds for linux/darwin/win32 but NOT android-arm64,
 *      and its loader asks for exactly `prebuilds/android-arm64/pty.node`. The
 *      missing module took the whole plugin tree down with it, because
 *      `dsh-subprocess-local` requires it at import time. Fix: a pipe-based
 *      stand-in with the same API (kernels/dsh-sandbox/node-pty-shim), wired in
 *      with an npm `overrides` entry so a rebuild picks it up automatically.
 *   2. `koffi` (used by `dsh-sandbox-local`) DOES publish an Android build -
 *      `@koromix/koffi-android-arm64` - but only if the install is asked for that
 *      platform. Fix: `--os=android --cpu=arm64 --include=optional`.
 *   3. `sharp` has no android prebuild either; npm falls back to
 *      `@img/sharp-wasm32`, which is fine.
 *
 * `--ignore-scripts` keeps npm from trying to compile native code on this
 * machine (koffi's postinstall needs CMake and would fail the whole install).
 *
 * Output: $d/local-kernel/dsh-<version>.tar.gz + dsh-sandbox.json (manifest),
 * ready to be served to the phone exactly like the Termux bootstrap.
 *
 *   node tools/build-dsh-sandbox.mjs [--version 0.1.2-rc.1]
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PC_AGENT = path.resolve(HERE, '..');
const OUT_DIR = process.env.TERMDESK_LOCAL_KERNEL_DIR ?? path.join(os.homedir(), '.termdesk', 'local-kernel');
const SHIM = path.join(PC_AGENT, 'kernels', 'dsh-sandbox', 'node-pty-shim');

const args = process.argv.slice(2);
const vAt = args.indexOf('--version');
const version = vAt >= 0 ? args[vAt + 1] : '0.1.2-rc.1';
const BUNDLES = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-sdk-app'];

const stage = path.join(os.tmpdir(), `termdesk-dsh-build-${Date.now()}`, 'dsh-home', 'profiles', 'sdk');
fs.mkdirSync(stage, { recursive: true });
console.log(`stage: ${stage}`);

fs.writeFileSync(path.join(stage, 'package.json'), JSON.stringify({
  name: 'dsh-profile-sdk',
  private: true,
  dsh: { profile: { bundles: BUNDLES, patchReload: 'startup' } },
  overrides: { 'node-pty': 'file:./shim/node-pty' },
}, null, 2) + '\n');
fs.writeFileSync(path.join(stage, 'cordis.yml'), '# dsh profile root (empty entry list; composed from bundles + patches).\n[]\n');
fs.writeFileSync(path.join(stage, 'cordis.patch.yml'), '[]\n');
fs.mkdirSync(path.join(stage, 'shim'), { recursive: true });
fs.cpSync(SHIM, path.join(stage, 'shim', 'node-pty'), { recursive: true });

const install = ['install', '--no-audit', '--no-fund', '--loglevel=error', '--ignore-scripts',
  '--os=android', '--cpu=arm64', '--include=optional',
  `@deepseek-ai/dsh@${version}`, ...BUNDLES.map((b) => `${b}@${version}`)];
console.log('npm ' + install.join(' '));
execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', install, { cwd: stage, stdio: 'inherit' });

// The claims above are checked, not assumed: a tree that does not contain the
// Android koffi build or the shim would fail on the phone at boot time.
const modules = path.join(stage, 'node_modules');
const required = [
  ['@koromix/koffi-android-arm64', 'directory'],
  ['node-pty/package.json', 'file'],
  ['@deepseek-ai/dsh/lib/bin.js', 'file'],
];
for (const [rel, kind] of required) {
  const full = path.join(modules, rel);
  const ok = kind === 'file' ? fs.existsSync(full) : fs.existsSync(full) && fs.statSync(full).isDirectory();
  if (!ok) throw new Error(`the staged tree is missing ${rel} (expected a ${kind})`);
}
const ptyVersion = JSON.parse(fs.readFileSync(path.join(modules, 'node-pty/package.json'), 'utf8')).version;
if (!String(ptyVersion).includes('android-pipes')) {
  throw new Error(`node-pty was not overridden by the shim (got ${ptyVersion})`);
}

const outFile = path.join(OUT_DIR, `dsh-${version}.tar.gz`);
fs.mkdirSync(OUT_DIR, { recursive: true });
execFileSync('tar', ['-czf', outFile, '-C', path.dirname(path.dirname(path.dirname(stage))), '.']);

const bytes = fs.readFileSync(outFile);
const manifest = {
  name: 'TermDesk 本地 DSH',
  kind: 'dsh-sandbox-tree',
  abi: 'android-arm64',
  version,
  package: path.basename(outFile),
  sizeBytes: bytes.length,
  sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  installDir: '.dsh',
  dshHome: '.dsh',
  entry: '.dsh/profiles/sdk/node_modules/@deepseek-ai/dsh/lib/bin.js',
  builtAt: new Date().toISOString(),
};
fs.writeFileSync(path.join(OUT_DIR, 'dsh-sandbox.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`\n${outFile}`);
console.log(`  ${(bytes.length / 1024 / 1024).toFixed(1)} MB  sha256 ${manifest.sha256.slice(0, 16)}…`);
console.log(`  ${path.join(OUT_DIR, 'dsh-sandbox.json')}`);
