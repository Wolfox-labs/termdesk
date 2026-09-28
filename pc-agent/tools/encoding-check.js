/**
 * Verify that spawning PowerShell from Node preserves non-ASCII text end to end.
 * Chinese service names are the canary: if encoding is wrong they turn into
 * mojibake and the whole UI shows garbage.
 *
 *   node tools/encoding-check.js
 */
import { runJsonArray, resolveShell } from '../src/exec.js';

console.log(`shell: ${resolveShell()}`);

const SCRIPT = `@(
  [PSCustomObject]@{ name = 'ADPSvc'; label = '聚合数据平台服务' },
  [PSCustomObject]@{ name = 'autotimesvc'; label = '手机网络时间' }
) | ConvertTo-Json -Depth 3`;

let failures = 0;
const check = (name, actual, expected) => {
  const ok = actual === expected;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${JSON.stringify(actual)}`);
  if (!ok) {
    failures += 1;
    console.log(`      expected ${JSON.stringify(expected)}`);
  }
};

try {
  const rows = await runJsonArray(SCRIPT, { timeoutMs: 20000 });
  check('row count', rows.length, 2);
  check('first label', rows[0]?.label, '聚合数据平台服务');
  check('second label', rows[1]?.label, '手机网络时间');
  check('first name', rows[0]?.name, 'ADPSvc');
} catch (err) {
  console.log(`FAIL  threw: ${err.message}`);
  failures += 1;
}

// Also confirm real service data round-trips, not just synthetic strings.
try {
  const real = await runJsonArray(
    "Get-Service | Where-Object { $_.DisplayName -match '聚合' } | Select-Object -First 1 -Property Name,DisplayName | ConvertTo-Json -Depth 3",
    { timeoutMs: 30000 },
  );
  if (real.length === 0) {
    console.log('SKIP  no matching real service found');
  } else {
    check('real service name', real[0]?.Name, 'ADPSvc');
  }
} catch (err) {
  console.log(`FAIL  real lookup threw: ${err.message}`);
  failures += 1;
}

console.log(failures === 0 ? '\nEncoding: ALL PASS' : `\nEncoding: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
