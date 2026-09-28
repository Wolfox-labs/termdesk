/**
 * Fourth probe: find a merge strategy that shows errors AND keeps streaming.
 *
 * Findings so far: wrapping as `Invoke-Expression $cmd 2>&1 | ForEach-Object`
 * swallowed the error completely (empty output, not even on stderr), while
 * `Out-String` surfaced it but buffers the whole result and destroys streaming.
 *
 * Each variant below runs inside the real REPL loop and is scored on both
 * error visibility and incremental output.
 *
 *   node tools/pty-probe4.js
 */
import { spawn } from 'node:child_process';

const HEADER = `
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
`;

/** Each variant is the body that runs a decoded command stored in $cmd. */
const VARIANTS = {
  'call-operator 2>&1':
    '& { Invoke-Expression $cmd } 2>&1 | ForEach-Object { [Console]::Out.WriteLine([string]$_) }',

  'append 2>&1 to command':
    'Invoke-Expression ($cmd + " 2>&1") | ForEach-Object { [Console]::Out.WriteLine([string]$_) }',

  'merge via Out-String (buffers)':
    'Invoke-Expression $cmd 2>&1 | Out-String | ForEach-Object { [Console]::Out.WriteLine($_.TrimEnd()) }',

  'dot-source 2>&1':
    '. { Invoke-Expression $cmd } 2>&1 | ForEach-Object { [Console]::Out.WriteLine([string]$_) }',
};

function buildLoop(body) {
  return `${HEADER}
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  try {
    $req = $line | ConvertFrom-Json
    $cmd = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($req.c))
    $global:LASTEXITCODE = 0
    ${body}
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
}

function makeSession(body) {
  const child = spawn(
    'powershell.exe',
    ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', buildLoop(body)],
    { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] },
  );
  let buffer = '';
  const listeners = [];
  let nextId = 1;
  let lastDataAt = 0;
  const chunkTimes = [];

  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    chunkTimes.push(Date.now());
    let idx;
    while ((idx = buffer.indexOf('__TD_END_')) !== -1) {
      const end = buffer.indexOf('__', idx + 9);
      if (end === -1) break;
      const [id, code] = buffer.slice(idx + 9, end).split('_');
      const output = buffer.slice(0, idx);
      buffer = buffer.slice(end + 2);
      const waiter = listeners.shift();
      if (waiter) waiter({ output, code: Number(code), id: Number(id) });
    }
  });

  const run = (cmd) =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      const timer = setTimeout(() => reject(new Error(`timeout: ${cmd}`)), 30000);
      listeners.push((res) => {
        clearTimeout(timer);
        resolve(res);
      });
      child.stdin.write(`${JSON.stringify({ id, c: Buffer.from(cmd, 'utf8').toString('base64') })}\n`);
    });

  return { child, run, chunkTimes, resetChunks: () => { chunkTimes.length = 0; } };
}

const verdicts = [];

for (const [name, body] of Object.entries(VARIANTS)) {
  const s = makeSession(body);
  await new Promise((r) => setTimeout(r, 900));

  // 1. error visibility
  const err = await s.run('Get-Item C:\\definitely-not-here-12345');
  const showsError = /ERR|error|找不到|Cannot find|not exist/i.test(err.output);

  // 2. normal output still fine
  const ok = await s.run('Write-Output "normal-ok"');
  const normalOk = ok.output.includes('normal-ok');

  // 3. streaming: 4 ticks over ~1.4s should produce early chunks
  s.resetChunks();
  const t0 = Date.now();
  const slow = await s.run('1..4 | ForEach-Object { Write-Output "tick $_"; Start-Sleep -Milliseconds 350 }');
  const total = Date.now() - t0;
  const earlyChunks = s.chunkTimes.filter((t) => t - t0 < total * 0.6).length;
  const streamed = earlyChunks > 0 && ['tick 1', 'tick 4'].every((x) => slow.output.includes(x));

  // 4. exit code preserved
  const bad = await s.run('cmd /c exit 5');
  const exitOk = bad.code === 5;

  // 5. non-ASCII
  const cjk = await s.run('Write-Output "中文测试"');
  const cjkOk = cjk.output.includes('中文测试');

  const score = [showsError, normalOk, streamed, exitOk, cjkOk].filter(Boolean).length;
  verdicts.push({ name, score, showsError, normalOk, streamed, exitOk, cjkOk, sample: err.output.trim().slice(0, 70) });
  console.log(`\n### ${name}  (${score}/5)`);
  console.log(`  error visible : ${showsError}  ${JSON.stringify(sample(err.output))}`);
  console.log(`  normal output : ${normalOk}`);
  console.log(`  streaming     : ${streamed} (early chunks: ${earlyChunks}, total ${total}ms)`);
  console.log(`  exit code     : ${exitOk} (code=${bad.code})`);
  console.log(`  non-ASCII     : ${cjkOk}`);
  s.child.kill();
  await new Promise((r) => setTimeout(r, 300));
}

function sample(s) { return s.trim().slice(0, 70); }

console.log('\n================');
const best = verdicts.filter((v) => v.score === 5);
if (best.length > 0) {
  console.log(`WINNER (5/5): ${best.map((b) => b.name).join(' | ')}`);
} else {
  const top = verdicts.slice().sort((a, b) => b.score - a.score)[0];
  console.log(`BEST: ${top.name} (${top.score}/5)`);
}
process.exit(0);
