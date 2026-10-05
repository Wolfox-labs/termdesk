/**
 * Approvals inside the chat pipeline: does a permission question actually reach
 * the phone, and does the phone's answer actually reach the engine?
 *
 * Everything here is stubbed — a fake ACP permission request and a fake Codex
 * approval — so no kernel is spawned and no model is called. What is being
 * proved is the wiring between three things that used to disagree: the engine's
 * request, the phone's answer, and the transcript.
 *
 *   node tools/chat-approval-test.js
 */
import { ChatManager } from '../src/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};
const tick = (ms = 30) => new Promise((r) => setTimeout(r, ms));

const frames = [];
const manager = new ChatManager();
manager.attach((payload) => frames.push(payload));
const approvalsFor = (chatId) => frames.filter((f) => f.event === 'chat.approval' && !f.state && f.chatId === chatId);

// ---- ACP: the kernel's own option vocabulary is what the phone answers in ----
const acpChat = manager.create({ engine: 'opencode', cwd: process.cwd() });
check('an ACP chat can be created', acpChat.ok === true, acpChat.message ?? '');
const chatId = acpChat.chat.id;
manager.acpSessions.set('ses_test', manager.get(chatId));

const acpOptions = [
  { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' },
  { optionId: 'allow_always', name: 'Always allow', kind: 'allow_always' },
  { optionId: 'reject_once', name: 'Reject', kind: 'reject_once' },
  { optionId: 'cancel', name: 'Cancel', kind: 'reject_always' },
];
const respondArgs = [];
const acpInfo = {
  sessionId: 'ses_test',
  toolCall: { title: 'bash: npm test', kind: 'execute' },
  options: acpOptions,
  defaultOptionId: 'allow_once',
  respond: (id) => respondArgs.push(id),
};
const pending = manager.handleAcpPermission('opencode', acpInfo);
await tick();

const asked = approvalsFor(chatId)[0];
check('the phone is asked about an ACP permission', Boolean(asked), `${approvalsFor(chatId).length} request(s)`);
check('the question names the tool', `${asked?.title} ${asked?.detail}`.includes('npm test'), asked?.title);
check('only answers we speak are offered',
  asked?.options?.map((o) => o.id).join(',') === 'allow_once,allow_always', asked?.options?.map((o) => o.id).join(','));
check('the kernel is not answered before the phone decides', respondArgs.length === 0);

const answered = manager.resolveApproval({ requestId: asked.requestId, optionId: 'allow_always' });
check('the phone answer is accepted', answered.ok === true, answered.message ?? '');
await pending;
check('the kernel receives the phone choice', respondArgs.join(',') === 'allow_always', respondArgs.join(','));

const note = manager.get(chatId).events.find((e) => e.name === 'permission');
check('the decision is written into the conversation', Boolean(note), note?.text);
check('the note says what was allowed', String(note?.text).includes('总是允许'), note?.text);

// ---- ACP with no phone attached: the kernel's own default, immediately ------
manager.detach();
const offlineArgs = [];
await manager.handleAcpPermission('opencode', {
  sessionId: 'ses_test',
  toolCall: { title: 'bash: rm -rf build', kind: 'execute' },
  options: acpOptions,
  defaultOptionId: 'reject_once',
  respond: (id) => offlineArgs.push(id),
});
check('without a phone the kernel default is used at once',
  offlineArgs.length === 1 && offlineArgs[0] === null,
  JSON.stringify(offlineArgs));
manager.attach((payload) => frames.push(payload));

// ---- Codex: the request becomes a question, the answer becomes a decision ---
const codexChat = manager.create({ engine: 'codex', cwd: process.cwd() });
codexChat.chat.threadId = 'thread-1';
const codexId = codexChat.chat.id;
manager.get(codexId).threadId = 'thread-1';
manager.codexThreads.set('thread-1', manager.get(codexId));

frames.length = 0;
const decision = manager.answerCodexApproval('item/commandExecution/requestApproval', {
  threadId: 'thread-1',
  command: 'npm',
  commandLine: ['npm', 'run', 'build'],
});
await tick();
const codexAsk = approvalsFor(codexId)[0];
check('the phone is asked about a Codex approval', Boolean(codexAsk), codexAsk?.title);
check('the question carries the actual command', String(codexAsk?.detail).includes('npm run build'), codexAsk?.detail);
check('Codex offers the same three answers',
  codexAsk?.options?.map((o) => o.id).join(',') === 'allow_once,allow_always,deny');
manager.resolveApproval({ requestId: codexAsk.requestId, optionId: 'allow_always' });
check('the answer becomes a Codex decision',
  JSON.stringify(await decision) === JSON.stringify({ decision: 'approved_for_session' }),
  JSON.stringify(await decision));

frames.length = 0;
const denial = manager.answerCodexApproval('execCommandApproval', { threadId: 'thread-1', command: 'curl example.com' });
await tick();
manager.resolveApproval({ requestId: approvalsFor(codexId)[0].requestId, optionId: 'deny' });
check('a denial becomes a Codex denial', JSON.stringify(await denial) === JSON.stringify({ decision: 'denied' }),
  JSON.stringify(await denial));

// ---- closing a conversation settles what it was waiting for -----------------
frames.length = 0;
const stranded = [];
void manager.handleAcpPermission('opencode', {
  sessionId: 'ses_test',
  toolCall: { title: 'write: a.txt', kind: 'write' },
  options: acpOptions,
  defaultOptionId: 'allow_once',
  respond: (id) => stranded.push(id),
});
await tick();
check('the chat-close case starts from a pending request', manager.approvals.pending().length === 1);
manager.close(chatId);
await tick();
check('closing the chat settles the pending permission',
  stranded.length === 1 && stranded[0] === null, JSON.stringify(stranded));
check('nothing is left pending', manager.approvals.pending().length === 0);

manager.disposeAll();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nChat approvals: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);