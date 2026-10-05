/**
 * ACP streaming: the answer must survive.
 *
 * A kernel streams its reply as chunks and never sends a finished copy of it, so
 * the chunks ARE the message. This is the regression test for the bug that was
 * reported from the device: the phone showed a turn's tool rows and not one word
 * of its text, because the chunks were being treated as replaceable previews
 * (a Codex idea, where an authoritative completion follows) and the answer was
 * deleted at the end of every turn.
 *
 *   node tools/acp-stream-test.js
 */
import { ChatManager } from '../src/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const frames = [];
const manager = new ChatManager();
manager.attach((payload) => frames.push(payload));

const created = manager.create({ engine: 'opencode', cwd: process.cwd() });
const chat = manager.get(created.chat.id);
manager.acpSessions.set('ses_stream', chat);

// Three chunks, as an ACP kernel sends them.
for (const piece of ['Hel', 'lo ', 'world']) {
  manager.handleAcpUpdate('opencode', 'ses_stream', {
    sessionUpdate: 'agent_message_chunk',
    content: { type: 'text', text: piece },
  });
}

const messages = chat.events.filter((e) => e.kind === 'message' && e.role === 'assistant');
check('the streamed chunks become ONE message', messages.length === 1, `${messages.length} message records`);
check('the whole answer is kept', messages[0]?.text === 'Hello world', JSON.stringify(messages[0]?.text));

const streamed = frames.filter((f) => f.event === 'chat.event' && f.item?.role === 'assistant');
check('each chunk was pushed to the phone', streamed.length === 3, `${streamed.length} frames`);
check('and they carry the same seq, so the phone replaces rather than appends',
  new Set(streamed.map((f) => f.item.seq)).size === 1,
  [...new Set(streamed.map((f) => f.item.seq))].join(','));
check('the last frame carries the full text', streamed.at(-1)?.item?.text === 'Hello world', streamed.at(-1)?.item?.text);

// Tool rows are not streamed, and must be unaffected.
manager.handleAcpUpdate('opencode', 'ses_stream', {
  sessionUpdate: 'tool_call',
  toolCallId: 'c1',
  title: 'bash: echo hi',
  kind: 'execute',
  status: 'in_progress',
});
check('a tool call is still recorded', chat.events.some((e) => e.kind === 'tool'), '');

// Ending the turn must not delete anything, and must stop the streaming cursor.
const before = chat.events.length;
manager.finishAcpTurn(chat, 'end_turn');
check('the turn end keeps every record', chat.events.length > before);
check('no record is left marked as streaming', chat.events.every((e) => !e.streaming));
const final = frames.filter((f) => f.event === 'chat.event' && f.item?.role === 'assistant').at(-1);
check('the phone is told the text is final', final?.item?.streaming === false);

manager.disposeAll();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nACP stream: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);