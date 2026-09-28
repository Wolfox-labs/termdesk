/**
 * Static checks for the unified chat pipeline (engine = dsh | codex).
 *
 * No network, no live LLM, no running agent: this exercises the pure mapping
 * and argument-building logic that `chat.js` / `engines.js` share, plus the
 * encodeFrame "payload must not carry `type`" invariant that once silently
 * swallowed `ai.finished`.
 *
 *   node tools/chat-engine-test.js
 */
import { encodeFrame } from '../src/protocol.js';
import { buildCodexExecArgs, killProcessTree, findCodex, ENGINES } from '../src/engines.js';
import { ChatManager, codexEventToChatEvent, CHAT_ENGINES } from '../src/chat.js';

let passes = 0;
let failures = 0;
function check(name, ok, detail = '') {
  if (ok) {
    passes += 1;
    console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`);
  } else {
    failures += 1;
    console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`);
  }
}

// --- engine vocabulary -----------------------------------------------------

check('CHAT_ENGINES is codex+dsh', CHAT_ENGINES.includes('codex') && CHAT_ENGINES.includes('dsh'),
  CHAT_ENGINES.join(','));
check('engines task list matches chat engines', ENGINES.every((e) => CHAT_ENGINES.includes(e)),
  ENGINES.join(','));

// --- buildCodexExecArgs: fresh turn vs thread resume -----------------------

{
  const fresh = buildCodexExecArgs({ prompt: 'hi', cwd: 'C:\\work' });
  check('fresh turn uses codex exec', fresh[0] === 'exec' && fresh.includes('--json'), fresh.join(' '));
  check('fresh turn pins cwd', fresh.includes('-C') && fresh.includes('C:\\work'));
  check('fresh turn does not resume', !fresh.includes('resume'));
  check('prompt is the trailing positional', fresh[fresh.length - 1] === 'hi');
}

{
  const resumed = buildCodexExecArgs({
    prompt: 'again',
    cwd: 'C:\\work',
    resumeThreadId: 'thread-abc123',
  });
  check('follow-up turn resumes the thread', resumed[0] === 'exec' && resumed[1] === 'resume'
    && resumed[2] === 'thread-abc123', resumed.join(' '));
  check('resume keeps --json', resumed.includes('--json'));
  check('resume does not re-pass -C', !resumed.includes('-C'));
  check('resume prompt is trailing', resumed[resumed.length - 1] === 'again');
}

{
  const routed = buildCodexExecArgs({
    prompt: 'x',
    cwd: 'C:\\work',
    provider: 'deepseek',
    model: 'deepseek-flash',
  });
  check('provider rides along as a config override',
    routed.includes('-c') && routed.includes('model_provider=deepseek'), routed.join(' '));
  check('model rides along as a config override',
    routed.includes('model=deepseek-flash'));
}

{
  const noRoute = buildCodexExecArgs({ prompt: 'x', cwd: 'C:\\work' });
  check('omitted provider/model add no -c flags', !noRoute.includes('-c'), noRoute.join(' '));
}

check('findCodex resolves to a string path or PATH name', typeof findCodex() === 'string', findCodex());
check('killProcessTree tolerates a missing child', (() => {
  try { killProcessTree(null); killProcessTree({ killed: true }); return true; }
  catch { return false; }
})());

// --- codexEventToChatEvent: one vocabulary, no `type` in records -----------

/** Every mapped record must avoid `type` (encodeFrame pitfall) and use `kind`. */
function assertSafeRecord(name, record) {
  if (record === null) {
    check(name, true, 'null (no user-visible content)');
    return;
  }
  const keys = Object.keys(record);
  check(`${name}: no "type" key`, !keys.includes('type'), keys.join(','));
  check(`${name}: carries a kind`, typeof record.kind === 'string' && record.kind.length > 0, record.kind);
  check(`${name}: text is a string`, typeof record.text === 'string');
}

{
  const rec = codexEventToChatEvent({ type: 'thread.started', thread_id: '0123456789abcdef' });
  assertSafeRecord('thread.started', rec);
  check('thread.started keeps the full id in meta', rec?.meta?.threadId === '0123456789abcdef');
  check('thread.started announces the short id', (rec?.text ?? '').includes('01234567'),
    rec?.text);
}

{
  const rec = codexEventToChatEvent({ type: 'turn.started' });
  assertSafeRecord('turn.started', rec);
  check('turn.started maps to the turn kind', rec?.kind === 'turn' && rec?.meta?.state === 'started');
}

{
  const rec = codexEventToChatEvent({ type: 'turn.completed', usage: { output_tokens: 42, input_tokens: 7 } });
  assertSafeRecord('turn.completed', rec);
  check('turn.completed closes the turn', rec?.kind === 'turn' && rec?.meta?.state === 'ended');
  check('turn.completed records usage', rec?.meta?.tokens === 42 && rec?.meta?.inputTokens === 7);
}

{
  const started = codexEventToChatEvent({
    type: 'item.started',
    item: { type: 'command_execution', command: 'echo hi' },
  });
  assertSafeRecord('command started', started);
  check('command start maps to tool', started?.kind === 'tool' && started?.name === 'command_execution');
  check('command start is running', started?.meta?.state === 'running');

  const done = codexEventToChatEvent({
    type: 'item.completed',
    item: { type: 'command_execution', command: 'echo hi', exit_code: 0, aggregated_output: 'hi\n' },
  });
  assertSafeRecord('command done', done);
  check('command result maps to tool_result', done?.kind === 'tool_result' && done?.role === 'tool');
  check('command result carries exit code', done?.meta?.exitCode === 0);
  check('command result carries output', done?.text === 'hi\n');
}

{
  const rec = codexEventToChatEvent({
    type: 'item.completed',
    item: { type: 'agent_message', text: 'TASK_DONE' },
  });
  assertSafeRecord('agent_message', rec);
  check('agent_message maps to assistant message',
    rec?.kind === 'message' && rec?.role === 'assistant' && rec?.text === 'TASK_DONE');
}

{
  const rec = codexEventToChatEvent({
    type: 'item.completed',
    item: { type: 'reasoning', text: 'thinking…' },
  });
  assertSafeRecord('reasoning', rec);
  check('reasoning maps to the reasoning kind', rec?.kind === 'reasoning' && rec?.role === 'assistant');
}

{
  const rec = codexEventToChatEvent({
    type: 'item.completed',
    item: { type: 'error', message: 'config warning' },
  });
  assertSafeRecord('error item', rec);
  check('codex error items surface as engine notes, not turn failures',
    rec?.kind === 'engine_note' && rec?.name === 'warning');
}

{
  const rec = codexEventToChatEvent({
    type: 'item.completed',
    item: {
      type: 'file_change',
      changes: [{ kind: 'add', path: 'a.txt' }, { kind: 'edit', path: 'b.txt' }],
    },
  });
  assertSafeRecord('file_change', rec);
  check('file_change lists the paths', (rec?.text ?? '').includes('add: a.txt')
    && (rec?.text ?? '').includes('edit: b.txt'));
}

check('unknown item types are ignored', codexEventToChatEvent({
  type: 'item.completed',
  item: { type: 'mystery' },
}) === null);
check('non-object input is ignored', codexEventToChatEvent(null) === null);

// --- encodeFrame invariant: mapped items never clobber the wire type -------

{
  const rec = codexEventToChatEvent({
    type: 'item.completed',
    item: { type: 'agent_message', text: 'hi' },
  });
  // Simulate the real chat.event payload shape.
  const frame = JSON.parse(encodeFrame('chat.event', {
    event: 'chat.event',
    chatId: 'c1',
    seq: 1,
    item: { seq: 1, at: 0, ...rec },
  }));
  check('encodeFrame keeps the frame type under a codex item', frame.type === 'chat.event', frame.type);
  check('mapped item still has no type field', frame.item.type === undefined);
  check('mapped item kind survives the round-trip', frame.item.kind === 'message');
}

// --- ChatManager.create: engine field and compatibility --------------------

{
  const mgr = new ChatManager();
  try {
    const dsh = mgr.create({ title: 'default' });
    check('create defaults to engine=dsh (compat)', dsh.ok === true && dsh.chat?.engine === 'dsh',
      dsh.chat?.engine);
    check('dsh chat summary carries engine', dsh.chat?.engine === 'dsh');
    check('dsh chat still reports provider/model', Boolean(dsh.chat?.provider && dsh.chat?.model),
      `${dsh.chat?.provider} / ${dsh.chat?.model}`);
    check('new chat starts idle and not ready', dsh.chat?.status === 'idle' && dsh.chat?.ready === false);

    const codex = mgr.create({ title: 'cx', engine: 'codex', provider: 'deepseek', model: 'deepseek-flash' });
    check('create accepts engine=codex', codex.ok === true && codex.chat?.engine === 'codex');
    check('codex chat keeps provider/model', codex.chat?.provider === 'deepseek'
      && codex.chat?.model === 'deepseek-flash');
    check('codex chat has no thread until first turn', codex.chat?.threadId === null);

    const bare = mgr.create({ title: 'cx2', engine: 'codex' });
    check('codex chat without a route is allowed', bare.ok === true && bare.chat?.engine === 'codex');
    check('unrouted codex chat leaves provider/model unset',
      bare.chat?.provider == null && bare.chat?.model == null);

    const bad = mgr.create({ title: 'nope', engine: 'gpt5' });
    check('create rejects an unknown engine', bad.ok === false && bad.code === 'bad_engine',
      bad.message);

    const listed = mgr.list();
    check('chat.list reports engine on every entry', listed.every((c) => typeof c.engine === 'string'),
      listed.map((c) => c.engine).join(','));
    check('chat detail reports engine', mgr.get(codex.chat.id)?.detail()?.engine === 'codex');

    // Transcript of a fresh chat must not smuggle a `type` field into items.
    const detail = mgr.get(dsh.chat.id).detail();
    check('transcript items never carry type',
      detail.events.every((e) => e.type === undefined),
      detail.events.map((e) => e.kind).join(','));
  } finally {
    mgr.disposeAll();
  }
}

// --- handleCodexLine side effects (thread id capture) ----------------------

{
  const mgr = new ChatManager();
  try {
    const created = mgr.create({ title: 't', engine: 'codex' });
    const chat = mgr.get(created.chat.id);
    // Simulate the JSONL line handler directly; no process spawn.
    mgr.handleCodexLine(chat, JSON.stringify({ type: 'thread.started', thread_id: 'thread-zzz999' }));
    check('thread.started stores the thread id for resume', chat.threadId === 'thread-zzz999');
    check('thread.started updates the wire sessionId', chat.sessionId === 'thread-zzz999');

    mgr.handleCodexLine(chat, JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'hello' },
    }));
    check('mapped events land in the transcript',
      chat.events.some((e) => e.kind === 'message' && e.text === 'hello'));
    check('stored events use kind, not type',
      chat.events.every((e) => e.type === undefined));
  } finally {
    mgr.disposeAll();
  }
}

console.log('');
console.log(`${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
