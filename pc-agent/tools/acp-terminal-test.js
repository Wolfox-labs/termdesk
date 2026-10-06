/**
 * The terminals ACP kernels ask this agent to run.
 *
 * ACP puts the command line on the client side, so these are the processes a
 * conversation actually runs in — the thing the phone has to be able to list,
 * read and type into. Everything here is checked against real child processes
 * (node itself, so the test behaves the same on Windows and in a sandbox):
 * output, exit codes, stdin, killing, releasing, and the byte cap that keeps a
 * runaway command from eating this machine's memory.
 *
 * Costs nothing: no kernel, no model, no network.
 *
 *   node tools/acp-terminal-test.js
 */
import { AcpTerminals, ACP_TERMINAL_METHODS } from '../src/kernels/acp-terminal.js';
import { AcpKernel } from '../src/kernels/acp.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const node = process.execPath;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- the five methods the spec defines, and the capability that announces them

for (const method of ['terminal/create', 'terminal/output', 'terminal/wait_for_exit', 'terminal/kill', 'terminal/release']) {
  check(`declares ${method}`, ACP_TERMINAL_METHODS.has(method));
}

{
  // The capability flag is what makes a kernel delegate at all; claiming it in a
  // comment while sending `terminal: false` is exactly the bug this guards.
  const kernel = new AcpKernel({ id: 'static', bin: node, args: ['-e', ''] });
  check('the kernel owns a terminal registry', Boolean(kernel.terminals?.create));
  const source = (await import('node:fs')).readFileSync(new URL('../src/kernels/acp.js', import.meta.url), 'utf8');
  check('initialize claims the terminal capability', /terminal:\s*true/.test(source));
  check('initialize no longer disclaims it', !/terminal:\s*false/.test(source));
  kernel.dispose();
}

// ---- a command runs, streams, and reports its exit

{
  const events = [];
  const terminals = new AcpTerminals({ onEvent: (e) => events.push(e) });
  const { terminalId } = terminals.create({
    sessionId: 's1',
    command: node,
    args: ['-e', "process.stdout.write('out-1\\n'); process.stderr.write('err-1\\n');"],
  });
  check('create returns an id', typeof terminalId === 'string' && terminalId.length > 0, terminalId);
  check('create is announced', events.some((e) => e.type === 'created' && e.terminal.id === terminalId));
  check('a running terminal is listed', terminals.list().some((t) => t.id === terminalId && t.state === 'running'));

  const status = await terminals.waitForExit({ terminalId });
  check('wait_for_exit reports success', status.exitCode === 0, JSON.stringify(status));

  const read = terminals.output({ terminalId });
  check('both streams are captured', read.output.includes('out-1') && read.output.includes('err-1'), JSON.stringify(read.output));
  check('nothing was dropped', read.truncated === false);
  check('the exit status is on the read too', read.exitStatus?.exitCode === 0);
  check('an exited terminal says so in the list', terminals.list().find((t) => t.id === terminalId)?.state === 'exited');
  check('exit is announced', events.some((e) => e.type === 'exited' && e.terminalId === terminalId));

  // ---- exiting non-zero is data, not an exception

  const failed = terminals.create({ command: node, args: ['-e', 'process.exit(3)'] });
  const failedStatus = await terminals.waitForExit(failed);
  check('a failing command reports its own code', failedStatus.exitCode === 3, JSON.stringify(failedStatus));

  // ---- a command that cannot start at all

  const missing = terminals.create({ command: 'termdesk-definitely-not-a-command', args: [] });
  const missingStatus = await terminals.waitForExit(missing);
  check('a command that cannot start still settles', missingStatus.exitCode === 127, JSON.stringify(missingStatus));
  check('and says why in its output', /not recognized|not found|ENOENT/i.test(terminals.output(missing).output));

  // ---- stdin: this is the half of "接管" that a read-only log cannot do

  const interactive = terminals.create({
    command: node,
    args: ['-e', "process.stdin.on('data', (d) => { process.stdout.write('echo:' + d.toString().trim()); process.exit(0); });"],
  });
  await sleep(150);
  check('write reaches the process', terminals.write(interactive.terminalId, 'hello\n') === true);
  const interactiveStatus = await terminals.waitForExit(interactive);
  check('the process answered the input', terminals.output(interactive).output.includes('echo:hello'), JSON.stringify(terminals.output(interactive).output));
  check('and exited normally', interactiveStatus.exitCode === 0);

  // ---- killing

  const long = terminals.create({ command: node, args: ['-e', 'setTimeout(() => {}, 30000)'] });
  await sleep(120);
  terminals.kill(long);
  const killed = await terminals.waitForExit(long);
  check('kill ends the process', killed.exitCode !== 0 || killed.signal !== null, JSON.stringify(killed));

  // ---- releasing

  terminals.release(long);
  check('a released terminal is forgotten', !terminals.list().some((t) => t.id === long.terminalId));
  let unknownThrew = false;
  try { terminals.output(long); } catch { unknownThrew = true; }
  check('reading a released terminal is an error, not empty output', unknownThrew);
  check('releasing is announced', events.some((e) => e.type === 'released' && e.terminalId === long.terminalId));

  terminals.dispose();
}

// ---- the byte cap, and the character boundary it must not break

{
  const terminals = new AcpTerminals();
  const { terminalId } = terminals.create({
    command: node,
    // 4000 three-byte characters: over a 2 KB cap by a wide margin.
    args: ['-e', "process.stdout.write('中'.repeat(4000));"],
    outputByteLimit: 2048,
  });
  await terminals.waitForExit({ terminalId });
  const read = terminals.output({ terminalId });
  const bytes = Buffer.byteLength(read.output, 'utf8');
  check('output is capped', bytes <= 2048, `${bytes} bytes`);
  check('the cap is reported', read.truncated === true);
  check('truncation happens at a character boundary', !read.output.startsWith('\uFFFD'), JSON.stringify(read.output.slice(0, 4)));
  check('what survives is still the real text', /^中+$/.test(read.output), `${read.output.length} chars`);
  terminals.dispose();
}

// ---- the environment the kernel asked for

{
  const terminals = new AcpTerminals();
  const { terminalId } = terminals.create({
    command: node,
    args: ['-e', "process.stdout.write(process.env.TERMDESK_ACP_TERM_TEST || 'missing');"],
    env: [{ name: 'TERMDESK_ACP_TERM_TEST', value: 'from-the-kernel' }],
  });
  await terminals.waitForExit({ terminalId });
  check('the kernel\'s environment reaches the command', terminals.output({ terminalId }).output === 'from-the-kernel');
  terminals.dispose();
}

// ---- cwd

{
  const terminals = new AcpTerminals();
  const { terminalId } = terminals.create({
    command: node,
    args: ['-e', "process.stdout.write(process.cwd());"],
    cwd: process.cwd(),
  });
  await terminals.waitForExit({ terminalId });
  const printed = terminals.output({ terminalId }).output.toLowerCase();
  check('the command runs in the directory the kernel named', printed.includes(process.cwd().toLowerCase().slice(-12)), printed);
  terminals.dispose();
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
