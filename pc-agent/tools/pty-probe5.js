/**
 * Fifth probe: scope semantics of the surviving merge variants.
 *
 * `& { }` runs the command in a child scope, `. { }` in the current scope.
 * For a terminal this decides whether a plain `$x = 5` or `cd` survives to the
 * next command, which users will hit within seconds.
 *
 *   node tools/pty-probe5.js
 */
import { spawn } from 'node:child_process';

const HEADER = `
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
`;

const VARIANTS = {
  'call-operator  (& { })': '& { Invoke-Expression $cmd } 2>&1 | ForEach-Object { [Console]::Out.WriteLine([string]$_) }',
  'dot-source     (. { })': '. { Invoke-Expression $cmd } 2>&1 | ForEach-Object { [Console]::Out.WriteLine([string]$_) }',
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

  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
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
      listeners.push((res) => { clearTimeout(timer); resolve(res); });
      child.stdin.write(`${JSON.stringify({ id, c: Buffer.from(cmd, 'utf8').toString('base64') })}\n`);
    });

  return { child, run };
}

for (const [name, body] of Object.entries(VARIANTS)) {
  const s = makeSession(body);
  await new Promise((r) => setTimeout(r, 900));

  console.log(`\n### ${name}`);

  // Plain (non-global) variable assignment — the common case.
  await s.run('$plainvar = "plain-value"');
  const read = await s.run('Write-Output "got=$plainvar"');
  const plainPersists = read.output.includes('got=plain-value');
  console.log(`  plain $x = ... persists : ${plainPersists}  ${JSON.stringify(read.output.trim())}`);

  // cd persistence.
  await s.run('Set-Location C:\\Windows');
  const cwd = await s.run('(Get-Location).Path');
  const cwdPersists = cwd.output.includes('C:\\Windows');
  console.log(`  Set-Location persists   : ${cwdPersists}  ${JSON.stringify(cwd.output.trim())}`);
  await s.run('Set-Location $env:USERPROFILE');

  // Function definition persistence.
  await s.run('function td-test-fn { "fn-ok" }');
  const fn = await s.run('td-test-fn');
  const fnPersists = fn.output.includes('fn-ok');
  console.log(`  function persists       : ${fnPersists}  ${JSON.stringify(fn.output.trim())}`);

  // Environment variable persistence.
  await s.run('$env:TD_TEST = "env-value"');
  const env = await s.run('Write-Output "env=$env:TD_TEST"');
  const envPersists = env.output.includes('env-ok') || env.output.includes('env=env-value');
  console.log(`  $env: persists          : ${envPersists}  ${JSON.stringify(env.output.trim())}`);

  // Array/object assignment.
  await s.run('$arr = 1,2,3');
  const arr = await s.run('Write-Output "count=$($arr.Count)"');
  const arrPersists = arr.output.includes('count=3');
  console.log(`  array persists          : ${arrPersists}  ${JSON.stringify(arr.output.trim())}`);

  const total = [plainPersists, cwdPersists, fnPersists, envPersists, arrPersists].filter(Boolean).length;
  console.log(`  SCORE: ${total}/5`);

  s.child.kill();
  await new Promise((r) => setTimeout(r, 300));
}
process.exit(0);
