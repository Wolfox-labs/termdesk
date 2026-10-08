/**
 * The chat facade still offers what it always offered.
 *
 * Why this exists: `chat.js` was split into `chat/chat.js` (session state) and
 * `chat/approvals.js` (the phone's answer path), and the manager is about to be
 * split further into per-kernel adapters. That kind of move fails in the one way
 * unit tests are worst at catching: a method that is *gone* rather than wrong.
 * Callers would throw at runtime, on a path nobody exercised, in production.
 *
 * So this file pins the public surface. It is deliberately a list of names, not
 * a behaviour test: `npm test` already covers behaviour, and a name list is the
 * only thing that notices an accidental deletion.
 *
 * When a method is renamed on purpose, update this list in the same commit — the
 * point is that the change is visible, not that it is forbidden.
 *
 *   node tools/chat-facade-test.js
 */
import { ChatManager, Chat, CHAT_ENGINES, CHAT_DEFAULTS, findChatDsh } from '../src/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/**
 * Every method the phone, the HTTP surface and the CLI route reach through.
 *
 * Grouped by what a reader would be looking for, so a missing name says which
 * part of the manager lost its way.
 */
const MANAGER_METHODS = {
  'routing and identity': [
    'attach', 'detach', 'emit', 'list', 'get',
    'findChatByThread', 'newestCodexChat', 'kernelFor',
  ],
  'lifecycle': [
    'reapIdle', 'dropIdleChat', 'dispose', 'disposeAll', 'failPending',
  ],
  'conversation': [
    'create', 'resume', 'setConfig', 'send', 'cancel', 'close',
  ],
  'dsh runtime': [
    'ensureRuntime', 'handleLine', 'handleStderr', 'handleSessionEvent',
    'handleAssistantChunk', 'pushAndEmit', 'emitEvent', 'discardPreviews',
  ],
  'codex app-server': [
    'codexServer', 'sendCodex', 'resumeCodex', 'handleCodexNotification',
    'finishCodexTurn', 'armCodexWatchdog', 'clearCodexWatchdog',
  ],
  'acp kernels': [
    'openAcpSession', 'ensureAcpSession', 'modelsFor', 'sendAcp', 'failAcp',
    'resumeAcp', 'handleAcpUpdate', 'finishAcpTurn', 'listAcpSessions', 'readAcpSession',
  ],
  'terminal surfaces': [
    'chatTerminals', 'chatTerminalRead', 'chatTerminalWrite', 'chatTerminalStop',
    'refreshCodexTerminals', 'handleAcpTerminal', 'emitTerminalList',
  ],
};

/**
 * The approval methods are mixed in from `chat/approvals.js` at construction, so
 * they are own properties of each manager rather than prototype methods. They are
 * checked on an instance below instead of in the prototype table.
 */
const APPROVAL_METHODS = ['answerCodexApproval', 'handleAcpPermission', 'resolveApproval'];

const proto = ChatManager.prototype;
for (const [group, names] of Object.entries(MANAGER_METHODS)) {
  const missing = names.filter((n) => typeof proto[n] !== 'function');
  check(`${group}: ${names.length} methods present`, missing.length === 0, missing.join(', '));
}

// A real instance, so the mixed-in methods are visible; the reaper is unref'd and
// the manager is disposed at the end so the test exits on its own.
const manager = new ChatManager();
const missingApprovals = APPROVAL_METHODS.filter((n) => typeof manager[n] !== 'function');
check(`approvals (mixed in from chat/approvals.js): ${APPROVAL_METHODS.length} methods present`,
  missingApprovals.length === 0, missingApprovals.join(', '));
check('an approval of an unknown request is refused, not queued',
  manager.resolveApproval({ requestId: 'nope', optionId: 'allow_once' })?.ok !== true);
check('an unknown option id is refused',
  manager.resolveApproval({ requestId: 'nope', optionId: 'whatever' })?.code === 'bad_option');
// The manager's idle reaper is unref'd, so the process still exits by itself.

const CHAT_METHODS = [
  'push', 'startTerminal', 'appendTerminal', 'finishTerminal',
  'terminalList', 'terminalDetail', 'summary', 'detail', 'request',
];
const chatProto = Chat.prototype;
const missingChat = CHAT_METHODS.filter((n) => typeof chatProto[n] !== 'function');
check(`Chat: ${CHAT_METHODS.length} methods present`, missingChat.length === 0, missingChat.join(', '));

// The event log cap moved into the chat module with the class it protects, so
// this is the cheapest place to prove the cap is still real.
const c = new Chat({ id: 't', title: 't', cwd: '.', engine: 'dsh', provider: null, model: null });
for (let i = 0; i < 1500; i += 1) c.push({ kind: 'message', role: 'user', text: `m${i}` });
check('the transcript stays capped', c.events.length === 1200, `length=${c.events.length}`);
check('and seq keeps counting past the cap', c.seq === 1500, `seq=${c.seq}`);

const summary = c.summary();
const SUMMARY_FIELDS = [
  'id', 'title', 'cwd', 'engine', 'provider', 'model', 'effort', 'status', 'ready',
  'sessionId', 'threadId', 'mode', 'createdAt', 'lastUsedAt', 'eventCount',
  'lastError', 'usage', 'live',
];
const missingFields = SUMMARY_FIELDS.filter((f) => !(f in summary));
check(`the phone's summary shape is unchanged (${SUMMARY_FIELDS.length} fields)`, missingFields.length === 0, missingFields.join(', '));

check('CHAT_ENGINES is a non-empty array', Array.isArray(CHAT_ENGINES) && CHAT_ENGINES.length > 0, CHAT_ENGINES.join(' / '));
check('CHAT_DEFAULTS still exports the manager limits',
  CHAT_DEFAULTS && CHAT_DEFAULTS.MAX_CHATS > 0 && CHAT_DEFAULTS.MAX_PROMPT_CHARS > 0 && CHAT_DEFAULTS.IDLE_TTL_MS > 0,
  JSON.stringify(CHAT_DEFAULTS));
check('findChatDsh is still exported for the DSH probe', typeof findChatDsh === 'function');

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
