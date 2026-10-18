/**
 * Where DSH really lives, and whether TermDesk can start it.
 *
 * The installed DeepSeek Harness keeps its CLI inside `resources/app.asar` and
 * its generated `dsh.cmd`/`dsh.ps1` launchers point at an older Roaming directory.
 * That combination made the kernel look "not installed" to plain Node even though
 * the runtime was healthy — the phone offered no DSH conversation at all.
 *
 * What is pinned here:
 *   - the asar reader finds an entry inside a real archive and refuses a missing
 *     one (the offset bug that would silently shift file contents is not observable
 *     here, but a wrong offset makes every lookup miss, which is);
 *   - resolution follows the documented order: explicit env, a standalone install,
 *     then the desktop app, and it reports the Electron host for an archive entry;
 *   - `launchArgs` keeps argv in the order DSH expects (script, then profile flags);
 *   - with `TERMDESK_DSH_LIVE=1`, the resolved runtime completes a real JSON-RPC
 *     handshake and rejects a resume for an unknown session. That last part starts
 *     a runtime but sends no prompt, so it costs no model tokens.
 *
 *   node tools/dsh-runtime-test.js
 *   TERMDESK_DSH_LIVE=1 node tools/dsh-runtime-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { spawn } from 'node:child_process';

import { asarHas, asarIndex, fileExists, isAsarPath } from '../src/kernels/asar.js';
import {
  DSH_ENTRY_SUFFIX,
  launchArgs,
  launchEnv,
  resolveDshRuntime,
} from '../src/kernels/dsh-runtime.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- asar: entry lookup inside a real archive --------------------------------
const installed = path.join(
  process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'),
  'Programs',
  'DeepSeek Harness',
);
const archive = path.join(installed, 'resources', 'app.asar');
const hasInstall = fs.existsSync(archive);

if (hasInstall) {
  const index = asarIndex(archive);
  check('the installed app.asar can be indexed', Boolean(index) && index.size > 1000, `${index?.size ?? 0} entries`);
  check('its header entry is found by full path',
    asarHas(archive, path.join(archive, 'dsh', DSH_ENTRY_SUFFIX)));
  check('an entry that is not there is reported missing',
    !asarHas(archive, path.join(archive, 'dsh', 'node_modules', '@deepseek-ai', 'dsh', 'lib', 'nope.js')));
  check('fileExists is asar-aware for a real file',
    fileExists(path.join(archive, 'dsh', DSH_ENTRY_SUFFIX)) === true);
  check('fileExists stays false for a path that is not in the archive',
    fileExists(path.join(archive, 'dsh', 'not-here.js')) === false);
} else {
  console.log('SKIP  asar checks: this machine has no DeepSeek Harness desktop install');
}

// --- resolution order --------------------------------------------------------
check('the entry suffix is the SDK CLI, not a launcher',
  DSH_ENTRY_SUFFIX.endsWith(path.join('@deepseek-ai', 'dsh', 'lib', 'bin.js')), DSH_ENTRY_SUFFIX);

const runtime = resolveDshRuntime();
check('DSH is resolved on this machine', runtime !== null,
  runtime ? `${runtime.source}: ${runtime.script ?? runtime.command}` : 'not found');

if (runtime) {
  check('a resolved runtime can be launched with profile flags',
    launchArgs(runtime, { profile: 'sdk' }).slice(-2).join(' ') === '--profile sdk',
    JSON.stringify(launchArgs(runtime, { profile: 'sdk' })));
  check('the profile flags follow the script, never precede it',
    !runtime.script || launchArgs(runtime, { profile: 'sdk' })[0] === runtime.script,
    JSON.stringify(launchArgs(runtime, { profile: 'sdk' })));
  if (isAsarPath(runtime.script)) {
    check('an archive entry is launched by the Electron host in Node mode',
      launchEnv(runtime).ELECTRON_RUN_AS_NODE === '1'
        && /DeepSeek Harness\.exe$/i.test(runtime.command),
      `${runtime.command} env=${JSON.stringify(runtime.env)}`);
  } else {
    check('a plain Node entry starts under this process Node', runtime.command === process.execPath, runtime.command);
  }
} else {
  console.log('SKIP  launch-argv checks: no runtime resolved');
}

// A temporary Node script stands in for a standalone install: the resolver must use
// `node <script>`, not try to execute the script itself.
{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-dsh-explicit-'));
  const script = path.join(dir, 'bin.js');
  fs.writeFileSync(script, '// inert stand-in for the SDK entry\n');
  const prior = process.env.TERMDESK_DSH;
  process.env.TERMDESK_DSH = script;
  try {
    const explicit = resolveDshRuntime();
    check('TERMDESK_DSH naming a Node script wins over every guess',
      explicit?.source === 'node-script' && explicit.script === script && explicit.command === process.execPath,
      JSON.stringify(explicit));
    check('the explicit entry keeps the inherited environment untouched',
      Object.keys(explicit?.env ?? {}).length === 0, JSON.stringify(explicit?.env));
  } finally {
    if (prior === undefined) delete process.env.TERMDESK_DSH; else process.env.TERMDESK_DSH = prior;
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// --- live wire check (opt-in, no prompt) -------------------------------------
async function liveHandshake(spec) {
  const child = spawn(spec.command, launchArgs(spec, { profile: 'sdk' }), {
    cwd: process.cwd(),
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
    env: launchEnv(spec, process.env),
  });
  const pending = new Map();
  let nextId = 1;
  const send = (method, params) => {
    const id = String(nextId++);
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 60000);
      pending.set(id, { resolve: (v) => { clearTimeout(timer); resolve(v); }, reject: (e) => { clearTimeout(timer); reject(e); } });
    });
  };
  readline.createInterface({ input: child.stdout }).on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith('{')) return;
    let frame;
    try { frame = JSON.parse(trimmed); } catch { return; }
    if (frame.id === undefined || frame.method !== undefined) return;
    const slot = pending.get(String(frame.id));
    if (!slot) return;
    pending.delete(String(frame.id));
    if (frame.error) slot.reject(new Error(frame.error.message ?? String(frame.error.code)));
    else slot.resolve(frame.result);
  });
  readline.createInterface({ input: child.stderr }).on('line', () => {});

  try {
    const init = await send('initialize', {
      cwd: process.cwd(),
      provider: 'deepseek-account',
      model: 'deepseek-flash',
    });
    check('the runtime completes the SDK handshake',
      init?.serverInfo?.name === 'deepseek-harness-sdk-runtime', JSON.stringify(init?.serverInfo));

    let refused = null;
    try {
      await send('session/resume', { sessionId: 'termdesk-no-such-session' });
    } catch (err) {
      refused = err.message;
    }
    check('resuming a session that does not exist is refused, not fabricated',
      typeof refused === 'string' && refused.length > 0, refused ?? '(unexpectedly succeeded)');

    await send('shutdown', undefined).catch(() => {});
  } finally {
    setTimeout(() => { if (child.exitCode === null) child.kill(); }, 500);
  }
}

if (process.env.TERMDESK_DSH_LIVE === '1' && runtime) {
  await liveHandshake(runtime);
} else if (runtime) {
  console.log('SKIP  live handshake (set TERMDESK_DSH_LIVE=1 to run it; it sends no prompt)');
}

const failed = results.filter((result) => !result.passed).length;
console.log(`\nDSH runtime: ${results.length - failed}/${results.length} passed`);
process.exit(failed === 0 ? 0 : 1);
