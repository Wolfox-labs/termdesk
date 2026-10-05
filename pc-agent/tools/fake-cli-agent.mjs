/**
 * A stub CLI kernel: a command line that behaves the way QoderWork and Command
 * Code were recorded to behave, so the shim adapter can be exercised for free.
 *
 *   <bin> --print --output-format stream-json [--resume <id>] "<prompt>"
 *   <bin> --list-sessions
 *
 * Output is newline-delimited JSON: one line naming the session, then one line
 * per piece of the answer. It also understands two flags the tests use:
 *
 *   --slow          emit a chunk every 120 ms (so cancel has something to stop)
 *   --fail          exit non-zero with a message on stderr
 *
 * Registered through the environment (`TERMDESK_CLI_KERNELS`), so production
 * code never learns it exists.
 */
const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const valueOf = (flag) => {
  const index = argv.indexOf(flag);
  return index >= 0 ? argv[index + 1] ?? null : null;
};
const write = (value) => process.stdout.write(JSON.stringify(value) + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (has('--list-sessions')) {
  // Two made-up sessions, oldest first, in the shape the manifest describes.
  write({ session_id: 'cli-0001', cwd: process.cwd(), title: 'stub session one', updated_at: '2026-10-05T10:00:00.000Z' });
  write({ session_id: 'cli-0002', cwd: process.cwd(), title: 'stub session two', updated_at: '2026-10-05T11:00:00.000Z' });
  process.exit(0);
}

const resumeId = valueOf('--resume') ?? valueOf('--session');
const prompt = argv.filter((a) => !a.startsWith('--')).at(-1) ?? '';
const session = resumeId ?? 'cli-0001';

if (has('--fail')) {
  process.stderr.write('stub CLI refused the call: quota exceeded\n');
  process.exit(3);
}

write({ session_id: session });
const body = resumeId
  ? `resumed ${resumeId}: you said "${prompt}"`
  : `stub CLI heard "${prompt}" and will answer in pieces`;
for (const piece of body.match(/.{1,16}/g) ?? []) {
  if (has('--slow')) await sleep(120);
  write({ type: 'assistant', text: piece });
}
write({ type: 'result', session_id: session, done: true });