/**
 * ACP event mapping and permission policy — pure functions, no process spawned.
 *
 * The mapping is the contract between an ACP kernel and the phone: every kernel
 * that speaks ACP must end up rendering through the SAME chat vocabulary the
 * Codex and DSH paths use. These checks pin that vocabulary down, including the
 * encodeFrame hazard (a record carrying its own `type` would silently overwrite
 * the wire frame type).
 *
 *   node tools/acp-map-test.js
 */
import { acpContentToText, acpToolContentToText, acpUpdateToChatEvents, acpSessionToSessionInfo, choosePermissionOption } from '../src/kernels/acp.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- content ---------------------------------------------------------------
check('plain string content', acpContentToText('hi') === 'hi');
check('text block', acpContentToText({ type: 'text', text: 'hello' }) === 'hello');
check('block without a type reads as text', acpContentToText({ text: 'x' }) === 'x');
check('array of blocks concatenates', acpContentToText([{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }]) === 'ab');
check('resource_link falls back to uri', acpContentToText({ type: 'resource_link', uri: 'file:///x' }) === 'file:///x');
check('embedded resource text wins', acpContentToText({ type: 'resource', resource: { text: 'inside' } }) === 'inside');
check('image is labelled, not dropped', acpContentToText({ type: 'image', uri: 'a.png' }).startsWith('[图片]'));
check('null content is empty, not undefined', acpContentToText(null) === '');

// --- tool content ----------------------------------------------------------
check('tool diff keeps the path', acpToolContentToText([{ type: 'diff', path: 'a.ts', newText: 'new' }]).includes('a.ts'));
check('tool content block unwraps', acpToolContentToText([{ type: 'content', content: { type: 'text', text: 'out' } }]) === 'out');
check('tool terminal marker', acpToolContentToText([{ type: 'terminal', terminalId: 't1' }]).includes('t1'));

// --- updates ---------------------------------------------------------------
const message = acpUpdateToChatEvents({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'hi' } });
check('assistant chunk -> streaming message',
  message.length === 1 && message[0].kind === 'message' && message[0].role === 'assistant' && message[0].streaming === true);
const thought = acpUpdateToChatEvents({ sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'hm' } });
check('thought chunk -> streaming reasoning', thought[0]?.kind === 'reasoning' && thought[0]?.streaming === true);
const user = acpUpdateToChatEvents({ sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'me' } });
check('user chunk is marked as an echo', user[0]?.role === 'user' && user[0]?.echo === true);

const call = acpUpdateToChatEvents({ sessionUpdate: 'tool_call', toolCallId: 'c1', title: 'Run tests', kind: 'execute', status: 'in_progress' });
check('tool_call carries name + state',
  call[0]?.kind === 'tool' && call[0]?.name === 'execute' && call[0]?.meta?.state === 'in_progress' && call[0]?.meta?.toolCallId === 'c1');
check('bare in_progress status flip is dropped',
  acpUpdateToChatEvents({ sessionUpdate: 'tool_call_update', toolCallId: 'c1', status: 'in_progress' }).length === 0);
const toolOut = acpUpdateToChatEvents({ sessionUpdate: 'tool_call_update', toolCallId: 'c1', status: 'completed', content: [{ type: 'content', content: { type: 'text', text: 'ok' } }] });
check('tool_call_update with output -> tool_result', toolOut[0]?.kind === 'tool_result' && toolOut[0]?.text === 'ok');
const plan = acpUpdateToChatEvents({ sessionUpdate: 'plan', entries: [{ content: 'a', status: 'completed' }, { content: 'b', status: 'pending' }] });
check('plan becomes an engine note', plan[0]?.kind === 'engine_note' && plan[0]?.text.includes('[x] a') && plan[0]?.text.includes('[ ] b'));
check('mode change is reported', acpUpdateToChatEvents({ sessionUpdate: 'current_mode_update', currentModeId: 'plan' })[0]?.text.includes('plan'));
check('unknown update kinds produce nothing', acpUpdateToChatEvents({ sessionUpdate: 'available_commands_update' }).length === 0);
check('missing update is safe', acpUpdateToChatEvents(undefined).length === 0);

// The whole vocabulary must be safe to embed in a frame payload.
const everyKind = [
  ...message, ...thought, ...user, ...call, ...toolOut, ...plan,
  ...acpUpdateToChatEvents({ sessionUpdate: 'current_mode_update', currentModeId: 'x' }),
];
check('no record carries a `type` key', everyKind.every((e) => !('type' in e)), `${everyKind.length} records`);

// --- session info ----------------------------------------------------------
const info = acpSessionToSessionInfo('opencode', { sessionId: 'ses_1', cwd: 'E:\\x', title: 'T', updatedAt: '2026-10-05T08:00:00Z' });
check('session maps to the phone shape',
  info.engine === 'opencode' && info.id === 'ses_1' && info.cwd === 'E:\\x' && info.native === true);
check('session without an id is dropped', acpSessionToSessionInfo('opencode', { title: 'x' }) === null);
check('epoch seconds become ISO', typeof acpSessionToSessionInfo('k', { sessionId: 'a', updatedAt: 1759600000 }).updatedAt === 'string');

// --- permissions -----------------------------------------------------------
const options = [
  { optionId: 'o1', kind: 'allow_once', name: 'Allow once' },
  { optionId: 'o2', kind: 'allow_always', name: 'Always' },
  { optionId: 'o3', kind: 'reject_once', name: 'Reject' },
];
check('allow policy picks allow_once', choosePermissionOption(options, 'allow')?.optionId === 'o1');
check('deny policy picks reject_once', choosePermissionOption(options, 'deny')?.optionId === 'o3');
check('name fallback when kinds differ',
  choosePermissionOption([{ optionId: 'z', name: 'Yes, proceed' }], 'allow')?.optionId === 'z');
check('nothing to choose -> null', choosePermissionOption([], 'allow') === null);
check('undefined options -> null', choosePermissionOption(undefined, 'allow') === null);

const failures = results.filter((r) => !r.passed).length;
console.log(`\nACP map: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);