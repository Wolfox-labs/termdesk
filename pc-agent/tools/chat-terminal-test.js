/**
 * The two kinds of command line a conversation can have.
 *
 * Codex runs commands inside its own process and tells us about them over the
 * app-server notifications, so those terminals can be listed, watched and
 * stopped — but not typed into, because `process/writeStdin` and
 * `command/exec/write` both require a process this client created. ACP kernels
 * are the other way round: the terminal is ours, so it can be driven.
 *
 * This test drives the notification path directly (no kernel, no model, no
 * network), because "the phone shows the command the agent ran" is exactly the
 * sort of thing that quietly stops working when an event name changes.
 *
 *   node tools/chat-terminal-test.js
 */
import { ChatManager } from '../src/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const manager = new ChatManager();
const emitted = [];
manager.attach((payload) => emitted.push(payload));

const created = manager.create({ engine: 'codex', cwd: 'C:/work', title: 'terminal test' });
if (!created?.ok) {
  console.error(`could not create a chat to test with: ${JSON.stringify(created)}`);
  process.exit(1);
}
// `create` answers with a summary for the wire; the live object is in the map.
const chat = manager.get(created.chat.id);
check('a chat exists to test with', Boolean(chat?.id));
chat.status = 'running';
chat.threadId = 'thread-1';
manager.codexThreads.set('thread-1', chat);

const item = {
  id: 'item_7',
  type: 'commandExecution',
  command: 'npm test',
  cwd: 'C:/work',
  status: 'inProgress',
};

// ---- a command the kernel started -----------------------------------------

manager.handleCodexNotification('item/started', { threadId: 'thread-1', item });
let listed = chat.terminalList();
check('the command appears as a terminal', listed.length === 1 && listed[0].id === 'item_7', JSON.stringify(listed));
check('it is marked as the kernel\'s own', listed[0]?.origin === 'agent', String(listed[0]?.origin));
check('it starts out running', listed[0]?.state === 'running', String(listed[0]?.state));
check('and it is honestly not writable', listed[0]?.canWrite === false, String(listed[0]?.canWrite));
check('the phone is told the list changed', emitted.some((p) => p.event === 'chat.terminals' && p.chatId === chat.id));

// ---- output while it runs --------------------------------------------------

manager.handleCodexNotification('item/commandExecution/outputDelta', {
  threadId: 'thread-1', itemId: 'item_7', delta: 'running tests\n',
});
check('streamed output is kept', chat.terminalDetail('item_7').output.includes('running tests'));
check(
  'output is not pushed at a phone that never opened the panel',
  !emitted.some((p) => p.event === 'chat.terminal.output' && p.terminalId === 'item_7'),
);

// Asking for the list is what says "somebody is looking".
await manager.chatTerminals(chat.id);
manager.handleCodexNotification('item/commandExecution/outputDelta', {
  threadId: 'thread-1', itemId: 'item_7', delta: '1 failing\n',
});
check(
  'once the panel is open, output streams',
  emitted.some((p) => p.event === 'chat.terminal.output' && p.terminalId === 'item_7' && p.chunk === '1 failing\n'),
);

// ---- it finishes -----------------------------------------------------------

manager.handleCodexNotification('item/completed', {
  threadId: 'thread-1',
  item: { ...item, status: 'completed', exitCode: 1, aggregatedOutput: 'running tests\n1 failing\n' },
});
const done = chat.terminalDetail('item_7');
check('the finished command is closed out', done.state === 'exited', done.state);
check('with its exit code', done.exitCode === 1, String(done.exitCode));
check(
  'the aggregate is not appended twice',
  (done.output.match(/running tests/g) ?? []).length === 1,
  JSON.stringify(done.output),
);

// ---- a turn that never streamed: the aggregate is all there is -------------

manager.handleCodexNotification('item/started', {
  threadId: 'thread-1',
  item: { id: 'item_8', type: 'commandExecution', command: 'echo hi', cwd: 'C:/work' },
});
manager.handleCodexNotification('item/completed', {
  threadId: 'thread-1',
  item: { id: 'item_8', type: 'commandExecution', command: 'echo hi', exitCode: 0, aggregatedOutput: 'hi\n' },
});
check('a command that never streamed still has its output', chat.terminalDetail('item_8').output === 'hi\n', JSON.stringify(chat.terminalDetail('item_8').output));

// ---- the interaction notice is recorded, not guessed -----------------------

manager.handleCodexNotification('item/commandExecution/terminalInteraction', {
  threadId: 'thread-1', itemId: 'item_8', stdin: 'y\n',
});
check('an interaction is recorded on its terminal', Boolean(chat.terminals.get('item_8')?.lastInputAt));

// ---- refusals say which case this is --------------------------------------

let refusal = null;
try {
  await manager.chatTerminalWrite(chat.id, 'item_8', 'hello\n');
} catch (err) {
  refusal = String(err?.message ?? err);
}
check('typing into a kernel-run command is refused', Boolean(refusal));
check(
  'and the refusal explains why, rather than failing vaguely',
  /内核自己执行|没有提供写入通道/.test(String(refusal)),
  String(refusal),
);

// ---- the list survives an unknown chat ------------------------------------

check('an unknown conversation answers nothing rather than inventing', (await manager.chatTerminals('nope')) === null);

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
