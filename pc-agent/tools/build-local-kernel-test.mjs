import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-local-kernel-build-test-'));
const script = fileURLToPath(new URL('./build-local-kernel.mjs', import.meta.url));
const input = path.join(root, 'bootstrap.tar');
const payload = Buffer.from('fixture bootstrap input; never installed or executed');
fs.writeFileSync(input, payload);

function run(args, outputDir, inputEnv) {
  const env = { ...process.env, TERMDESK_LOCAL_KERNEL_DIR: outputDir };
  if (inputEnv === undefined) delete env.TERMDESK_LOCAL_KERNEL_INPUT;
  else env.TERMDESK_LOCAL_KERNEL_INPUT = inputEnv;
  return spawnSync(process.execPath, [script, ...args], { cwd: root, encoding: 'utf8', env });
}

function assertBuild(outputDir, version) {
  const archive = path.join(outputDir, `bootstrap-${version}.tar.gz`);
  const compressed = fs.readFileSync(archive);
  assert.deepEqual(zlib.gunzipSync(compressed), payload);
  const manifest = JSON.parse(fs.readFileSync(path.join(outputDir, 'local-kernel.json'), 'utf8'));
  assert.equal(manifest.version, version);
  assert.equal(manifest.builtFrom, 'bootstrap.tar');
}

try {
  const missing = run([], path.join(root, 'missing-output'));
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /请指定 Termux bootstrap tar/);
  assert.match(missing.stderr, /TERMDESK_LOCAL_KERNEL_INPUT/);

  const positionalOut = path.join(root, 'positional-output');
  const positional = run([input], positionalOut);
  assert.equal(positional.status, 0, positional.stderr);
  assertBuild(positionalOut, 'v1');

  const flagOut = path.join(root, 'flag-output');
  const flagged = run(['--input', input, '--version', 'v2'], flagOut);
  assert.equal(flagged.status, 0, flagged.stderr);
  assertBuild(flagOut, 'v2');

  const envOut = path.join(root, 'env-output');
  const fromEnv = run([], envOut, input);
  assert.equal(fromEnv.status, 0, fromEnv.stderr);
  assertBuild(envOut, 'v1');
  console.log('PASS local-kernel input is explicit, and positional, --input, and environment inputs build correctly');
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
