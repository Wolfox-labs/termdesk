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
import { buildCodexExecArgs, killProcessTree, findCodex } from '../src/engines.js';
import { notificationToChatEvents, turnToChatEvents } from '../src/kernels/codex.js';
import { ChatManager, CHAT_ENGINES } from '../src/chat.js';

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
// The task surface that used to be mirrored by ENGINES is gone: the kernel
// table in kernels/registry.js is the only list now, and CHAT_ENGINES is
// derived from it.

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

// --- app-server mapping: one vocabulary, no `type` in records --------------

function assertSafeRecord(name, record) {
  check(`${name} has a kind and no wire type`, typeof record.kind === 'string' && record.type === undefined,
    record.kind ?? 'missing');
  check(`${name} carries text and role`, typeof record.text === 'string' && typeof record.role === 'string');
}

{
  const events = notificationToChatEvents('item/completed', { threadId: 't', item: { type: 'agentMessage', text: 'hi' } });
  check('agentMessage maps to one message', events.length === 1 && events[0].kind === 'message' && events[0].text === 'hi');
  assertSafeRecord('agent message', events[0]);
  check('completed agent message is not streaming', events[0].streaming === false);

  const cmdStart = notificationToChatEvents('item/started', { item: { type: 'commandExecution', command: 'dir' } });
  check('commandExecution start maps to a running tool',
    cmdStart[0]?.kind === 'tool' && cmdStart[0]?.meta?.state === 'running', cmdStart[0]?.kind);

  const cmdDone = notificationToChatEvents('item/completed', {
    item: { type: 'commandExecution', command: 'dir', aggregatedOutput: 'ok', exitCode: 0 },
  });
  check('commandExecution completion maps to tool_result',
    cmdDone[0]?.kind === 'tool_result' && cmdDone[0]?.meta?.exitCode === 0, cmdDone[0]?.kind);

  const reason = notificationToChatEvents('item/completed', { item: { type: 'reasoning', content: ['think'] } });
  check('reasoning maps on completion only', reason[0]?.kind === 'reasoning' && reason[0]?.text.includes('think'));
  check('reasoning start maps to nothing',
    notificationToChatEvents('item/started', { item: { type: 'reasoning', content: [] } }).length === 0);

  const delta = notificationToChatEvents('item/agentMessage/delta', { delta: 'abc' });
  check('streaming delta is marked streaming', delta[0]?.streaming === true && delta[0]?.text === 'abc');

  const turnStart = notificationToChatEvents('turn/started', { turn: { id: 'x' } });
  check('turn/started maps to a turn marker', turnStart[0]?.kind === 'turn' && turnStart[0]?.meta?.state === 'started');
  const turnDone = notificationToChatEvents('turn/completed', { turn: { id: 'x', status: 'interrupted' } });
  check('interrupted turn still reports ended', turnDone[0]?.kind === 'turn' && turnDone[0]?.meta?.state === 'ended');
  const turnFail = notificationToChatEvents('turn/completed', { turn: { id: 'x', status: 'failed', error: { message: 'boom' } } });
  check('failed turn reports failed', turnFail[0]?.meta?.state === 'failed' && turnFail[0]?.meta?.reason === 'boom');

  const files = notificationToChatEvents('item/completed', {
    item: { type: 'fileChange', changes: [{ kind: 'add', path: 'a.txt' }, { kind: 'edit', path: 'b.txt' }] },
  });
  check('fileChange lists the paths', (files[0]?.text ?? '').includes('add: a.txt')
    && (files[0]?.text ?? '').includes('edit: b.txt'));

  check('unknown item types are ignored',
    notificationToChatEvents('item/completed', { item: { type: 'mystery' } }).length === 0);
  check('unknown notification methods are ignored',
    notificationToChatEvents('something/else', {}).length === 0);

  const history = turnToChatEvents({ items: [
    { type: 'userMessage', content: [{ type: 'text', text: 'q' }] },
    { type: 'agentMessage', text: 'a' },
  ] });
  check('transcript turns map user first then assistant',
    history.length === 2 && history[0].role === 'user' && history[1].role === 'assistant',
    history.map((e) => e.role).join(','));
}
// --- encodeFrame invariant: mapped items never clobber the wire type -------

{
  const rec = notificationToChatEvents('item/completed', { item: { type: 'agentMessage', text: 'hi' } })[0];
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

// --- Codex chats are kernel-driven -----------------------------------------

{
  const mgr = new ChatManager();
  try {
    const created = mgr.create({ title: 't', engine: 'codex' });
    const chat = mgr.get(created.chat.id);
    check('a new codex chat has no native thread yet', chat.threadId === null);
    check('codex chats own no per-chat process', chat.child === null);
    // The native thread id arrives from the kernel on the first turn; the live
    // path is covered by tools/chat-codex-appserver-test.js and codex-ws-e2e.js.
  } finally {
    mgr.disposeAll();
  }
}
console.log('');
console.log(`${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
