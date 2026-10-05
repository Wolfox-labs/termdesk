/**
 * Build the local-kernel payload the phone installs.
 *
 * Input : the Termux bootstrap tar that was already rebuilt under this app's
 *         package name (242 MB, 8302 entries, 1431 symlinks).
 * Output: $d/local-kernel/bootstrap.tar.gz  +  local-kernel.json (manifest).
 *
 * Why gzip and not the .tar.xz that was built earlier: Android ships toybox,
 * whose tar handles -z (gzip) natively, so the phone can unpack the payload with
 * the tools it already has — including the symlinks, which is the part a naive
 * Java unzip gets wrong. That removes a decompression dependency from the client
 * entirely.
 *
 * The manifest is what the phone verifies against: size and sha256, so a partial
 * download or a corrupted transfer cannot be installed as a working sandbox.
 *
 *   node tools/build-local-kernel.mjs [input.tar] [--version v1]
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { pipeline } from 'node:stream/promises';

// Where the rebuilt Termux bootstrap actually lives on this machine. The first
// guess (.tmp/termux-build/stage) no longer exists: the payload was moved to
// the ignored scratch dir `$d/`, which is where the agent serves it from.
const DEFAULT_INPUT = 'E:/aiPic/termdesk/$d/dev.termdesk.app-f-droid-bootstrap-aarch64.tar';
const OUT_DIR = process.env.TERMDESK_LOCAL_KERNEL_DIR ?? path.join(os.homedir(), '.termdesk', 'local-kernel');

const args = process.argv.slice(2);
// `--version v1` used to be picked up as the input path, because the VALUE of a
// flag also does not start with "--". Skip a flag's value explicitly.
const flagValues = new Set();
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--version' || args[i] === '--out') flagValues.add(args[i + 1]);
}
const input = args.find((a) => !a.startsWith('--') && !flagValues.has(a)) ?? DEFAULT_INPUT;
const versionArg = args.indexOf('--version');
const version = versionArg >= 0 ? args[versionArg + 1] : 'v1';

if (!fs.existsSync(input)) {
  console.error(`找不到输入 tar：${input}`);
  process.exit(1);
}
fs.mkdirSync(OUT_DIR, { recursive: true });

const outFile = path.join(OUT_DIR, `bootstrap-${version}.tar.gz`);
console.log(`building ${outFile} from ${path.basename(input)}`);

const started = Date.now();
const source = fs.createReadStream(input);
await pipeline(source, zlib.createGzip({ level: 6 }), fs.createWriteStream(outFile));

const stat = fs.statSync(outFile);

/**
 * The hash the phone verifies is the hash of the file it DOWNLOADS.
 *
 * It used to be taken from the input stream on the way past, which identified
 * the tar and not the tar.gz - a verification that would have failed every
 * time, or worse, passed against the wrong file.
 */
const sha256 = await new Promise((resolve, reject) => {
  const digest = crypto.createHash('sha256');
  fs.createReadStream(outFile)
    .on('data', (chunk) => digest.update(chunk))
    .on('error', reject)
    .on('end', () => resolve(digest.digest('hex')));
});

const manifest = {
  name: 'TermDesk 本地内核',
  kind: 'termux-bootstrap',
  abi: 'aarch64',
  version,
  prefix: '/data/data/dev.termdesk.app/files/usr',
  package: 'bootstrap-' + version + '.tar.gz',
  sizeBytes: stat.size,
  sha256,
  /** Where the unpacked tree lives inside the app, and what must run to prove it. */
  installDir: 'usr',
  entryPoints: {
    shell: 'usr/bin/bash',
    apt: 'usr/bin/apt',
    python: 'usr/bin/python3',
    node: 'usr/bin/node',
    git: 'usr/bin/git',
  },
  healthCheck: { argv: ['usr/bin/bash', '-lc', 'echo termdesk-local-kernel-ok'] },
  builtAt: new Date().toISOString(),
  builtFrom: path.basename(input),
};
fs.writeFileSync(path.join(OUT_DIR, 'local-kernel.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');

console.log(`done in ${((Date.now() - started) / 1000).toFixed(0)}s`);
console.log(`  ${(stat.size / 1024 / 1024).toFixed(1)} MB  sha256=${sha256.slice(0, 16)}...`);
console.log(`  manifest -> ${path.join(OUT_DIR, 'local-kernel.json')}`);
