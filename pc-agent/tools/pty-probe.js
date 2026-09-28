/**
 * Probe: which approach gives a persistent, incremental PowerShell session?
 *
 * The terminal feature needs three properties:
 *   1. state persists between commands (cd, variables, env);
 *   2. output arrives while the command runs, not only after it ends;
 *   3. command boundaries are detectable.
 *
 * Guessing here would waste a lot of work, so this measures the candidates.
 *
 *   node tools/pty-probe.js
 */
import { spawn } from 'node:child_process';

const CANDIDATES = [
  {
    name: 'A: -Command - (stdin as script)',
    args: ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', '-'],
    // Feed a command, then wait to see if output arrives before stdin closes.
    feed: (child) => {
      child.stdin.write('Write-Output "FIRST_MARKER"\n');
      setTimeout(() => child.stdin.write('Write-Output "SECOND_MARKER"\n'), 600);
    },
  },
  {
    name: 'B: custom REPL loop (Console.In.ReadLine)',
    args: [
      '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
      'while($true){ $l=[Console]::In.ReadLine(); if($l -eq $null){break}; try{ Invoke-Expression $l }catch{ Write-Output ("ERR: "+$_.Exception.Message) }; [Console]::Out.WriteLine("__DONE__"); [Console]::Out.Flush() }',
    ],
    feed: (child) => {
      child.stdin.write('Write-Output "FIRST_MARKER"\n');
      setTimeout(() => child.stdin.write('Write-Output "SECOND_MARKER"\n'), 600);
    },
  },
];

function probe(candidate) {
  return new Promise((resolve) => {
    const child = spawn('powershell.exe', candidate.args, {
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    const stamps = [];
    const start = Date.now();
    let buffer = '';

    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      if (buffer.includes('FIRST_MARKER') && !stamps.some((s) => s.marker === 'first')) {
        stamps.push({ marker: 'first', at: Date.now() - start });
      }
      if (buffer.includes('SECOND_MARKER') && !stamps.some((s) => s.marker === 'second')) {
        stamps.push({ marker: 'second', at: Date.now() - start });
      }
    });

    candidate.feed(child);

    setTimeout(() => {
      const first = stamps.find((s) => s.marker === 'first');
      const second = stamps.find((s) => s.marker === 'second');
      // Incremental means the first marker appeared well before the second was
      // even sent (600ms in).
      const incremental = Boolean(first) && first.at < 550;
      console.log(`\n${candidate.name}`);
      console.log(`  first  @ ${first ? `${first.at}ms` : 'never'}`);
      console.log(`  second @ ${second ? `${second.at}ms` : 'never'}`);
      console.log(`  incremental: ${incremental ? 'YES' : 'NO'}`);
      console.log(`  raw output: ${JSON.stringify(buffer.slice(0, 200))}`);
      child.kill();
      resolve({ name: candidate.name, incremental, sawBoth: Boolean(first && second) });
    }, 2500);
  });
}

const outcomes = [];
for (const c of CANDIDATES) {
  outcomes.push(await probe(c));
}

console.log('\n------------');
const winner = outcomes.find((o) => o.incremental && o.sawBoth);
console.log(winner ? `USABLE: ${winner.name}` : 'NONE incremental — will need the sentinel design');
