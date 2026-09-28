/**
 * Third probe: make error output visible without breaking streaming.
 *
 * The previous design captured only stdout, so PowerShell's non-terminating
 * errors (the common case: file not found, bad path) vanished silently and the
 * exit code read 0. A terminal that hides errors is worse than useless.
 *
 * Testing the fix: merge the error stream into the output stream as text,
 * while confirming streaming still works.
 *
 *   node tools/pty-probe3.js
 */
import { spawn } from 'node:child_process';

// 2>&1 inside the pipeline merges errors into the object stream; the
// ForEach-Object then renders ErrorRecords as readable text instead of
// letting them disappear. Streaming is preserved because this stays a pipeline.
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
    Invoke-Expression $cmd 2>&1 | ForEach-Object {
      if ($_ -is [System.Management.Automation.ErrorRecord]) {
        [Console]::Out.WriteLine("ERR: " + $_.Exception.Message)
      } elseif ($_ -is [System.Management.Automation.WarningRecord]) {
        [Console]::Out.WriteLine("WARN: " + $_.Message)
      } else {
        [Console]::Out.WriteLine([string]$_)
      }
    }
    $code = $global:LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    [Console]::Out.WriteLine("ERR: " + $_.Exception.Message)
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
const listeners = [];

child.stdout.on('data', (chunk) => {
  buffer += chunk.toString('utf8');
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

// The case that failed before.
const missing = await run('Get-Item C:\\definitely-not-here-12345');
check('missing file surfaces an error', missing.output.includes('ERR:'),
  JSON.stringify(missing.output.trim().slice(0, 90)));

// Exit codes must still work.
const bad = await run('cmd /c exit 3');
check('non-zero exit code still reported', bad.code === 3, `code=${bad.code}`);

const ok = await run('Write-Output "fine"');
check('successful command unaffected', ok.output.includes('fine') && ok.code === 0, JSON.stringify(ok.output.trim()));

// Normal output must not be mangled by the pipeline.
const multi = await run('Write-Output "a"; Write-Output "b"; Write-Output "c"');
check('multi-line output intact', ['a', 'b', 'c'].every((x) => multi.output.split(/\r?\n/).includes(x)),
  JSON.stringify(multi.output.trim()));

// Non-ASCII still fine.
const cjk = await run('Write-Output "中文测试 你好世界"');
check('non-ASCII still survives', cjk.output.includes('中文测试'), JSON.stringify(cjk.output.trim()));

// Streaming must survive the pipeline change.
const t0 = Date.now();
let firstAt = null;
const onData = () => { if (firstAt === null) firstAt = Date.now() - t0; };
child.stdout.on('data', onData);
const slow = await run('1..4 | ForEach-Object { Write-Output "tick $_"; Start-Sleep -Milliseconds 350 }');
child.stdout.off('data', onData);
const total = Date.now() - t0;
check('streaming still incremental', firstAt !== null && firstAt < total * 0.6, `first @${firstAt}ms of ${total}ms`);
check('all ticks captured', ['tick 1', 'tick 4'].every((x) => slow.output.includes(x)));

// Objects (not just strings) must render, since users run real commands.
const obj = await run('Get-Process -Id $PID | Select-Object -Property ProcessName');
check('object output renders as text', obj.output.includes('ProcessName') || obj.output.includes('powershell'),
  JSON.stringify(obj.output.trim().slice(0, 80)));

// Exit code from a native tool that writes to stderr.
const git = await run('git --version');
check('native tool output works', /git version/i.test(git.output), JSON.stringify(git.output.trim()));

child.kill();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nProbe3: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 200);
