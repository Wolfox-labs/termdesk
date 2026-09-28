/**
 * Second probe: nail down the REPL design before building the terminal on it.
 *
 * Checks the things that actually break in practice:
 *   - does state persist between commands (cd, variables)?
 *   - are exit codes reported correctly, including reset between commands?
 *   - does long-running output stream incrementally, or only arrive at the end?
 *   - do quotes, pipes and non-ASCII survive the transport?
 *
 *   node tools/pty-probe2.js
 */
import { spawn } from 'node:child_process';

const LOOP = `
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  try {
    $req = $line | ConvertFrom-Json
    $cmd = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($req.c))
    $global:LASTEXITCODE = 0
    Invoke-Expression $cmd
    $code = $global:LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    Write-Output ("ERROR: " + $_.Exception.Message)
    $code = 1
  }
  [Console]::Out.WriteLine("__TD_END_$($req.id)_" + $code + "__")
  [Console]::Out.Flush()
}
`;

const child = spawn(
  'powershell.exe',
  ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', LOOP],
  { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] },
);

let buffer = '';
const events = [];
const listeners = [];

child.stdout.on('data', (chunk) => {
  const text = chunk.toString('utf8');
  buffer += text;
  events.push({ at: Date.now(), text });
  // Dispatch any completed command whose sentinel has arrived.
  let idx;
  while ((idx = buffer.indexOf('__TD_END_')) !== -1) {
    const end = buffer.indexOf('__', idx + 9);
    if (end === -1) break;
    const header = buffer.slice(idx + 9, end);
    const [id, code] = header.split('_');
    const output = buffer.slice(0, idx);
    buffer = buffer.slice(end + 2);
    const waiter = listeners.shift();
    if (waiter) waiter({ output, code: Number(code), id: Number(id) });
  }
});

let nextId = 1;
const pending = new Map();
function run(cmd) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => reject(new Error(`timeout on: ${cmd}`)), 40000);
    listeners.push((res) => {
      clearTimeout(timer);
      resolve(res);
    });
    const payload = Buffer.from(cmd, 'utf8').toString('base64');
    child.stdin.write(`${JSON.stringify({ id, c: payload })}\n`);
  });
}

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

await new Promise((r) => setTimeout(r, 900));

// --- state persistence ---
const a = await run('$global:PROBE_VAR = "persisted-value"; Write-Output $global:PROBE_VAR');
check('variable persists in-session', a.output.includes('persisted-value'), JSON.stringify(a.output.trim()));

const b = await run('Write-Output $global:PROBE_VAR');
check('variable readable by a later command', b.output.includes('persisted-value'), JSON.stringify(b.output.trim()));

const c = await run('Set-Location C:\\Windows; (Get-Location).Path');
check('working directory persists', c.output.includes('C:\\Windows'), JSON.stringify(c.output.trim()));

const d = await run('(Get-Location).Path');
check('cwd still C:\\Windows afterwards', d.output.includes('C:\\Windows'), JSON.stringify(d.output.trim()));

// --- exit codes ---
const ok = await run('cmd /c exit 0');
check('exit code 0 reported', ok.code === 0, `code=${ok.code}`);

const bad = await run('cmd /c exit 7');
check('non-zero exit code reported', bad.code === 7, `code=${bad.code}`);

const reset = await run('Write-Output "clean"');
check('exit code resets between commands', reset.code === 0, `code=${reset.code}`);

// --- quoting and non-ASCII ---
const quote = await run('Write-Output "quoted \'inner\' and | pipe"');
check('quotes and pipes survive', quote.output.includes('quoted') && quote.output.includes('pipe'), JSON.stringify(quote.output.trim()));

const cjk = await run('Write-Output "中文输出测试 聚合数据平台服务"');
check('non-ASCII survives round trip', cjk.output.includes('中文输出测试'), JSON.stringify(cjk.output.trim()));

const multi = await run('Write-Output "line1"; Write-Output "line2"');
check('multiple output lines preserved', multi.output.includes('line1') && multi.output.includes('line2'));

// --- streaming: output must arrive before the command finishes ---
const streamStart = Date.now();
let firstChunkAt = null;
const onData = () => { if (firstChunkAt === null) firstChunkAt = Date.now() - streamStart; };
child.stdout.on('data', onData);
const slow = await run('1..4 | ForEach-Object { Write-Output "tick $_"; Start-Sleep -Milliseconds 350 }');
child.stdout.off('data', onData);
const totalMs = Date.now() - streamStart;
check('long command output streams incrementally', firstChunkAt !== null && firstChunkAt < totalMs * 0.6,
  `first chunk @${firstChunkAt}ms of ${totalMs}ms total`);
check('all streamed output captured', ['tick 1', 'tick 2', 'tick 3', 'tick 4'].every((t) => slow.output.includes(t)));

// --- error handling ---
const err = await run('Get-Item C:\\definitely-not-here-12345');
check('failed command reports an error', err.output.toLowerCase().includes('error') || err.code !== 0,
  `code=${err.code} out=${JSON.stringify(err.output.trim().slice(0, 80))}`);

const thrown = await run('throw "deliberate"');
check('thrown exception is caught, session survives', thrown.code === 1, `code=${thrown.code}`);

const alive = await run('Write-Output "still-alive"');
check('session still usable after errors', alive.output.includes('still-alive'));

child.kill();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nProbe2: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
