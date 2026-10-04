/**
 * Static checks for live-conversation model / reasoning-effort control.
 *
 * No network, no live LLM, no kernel process: the Codex app-server is replaced
 * by a stub, so this only proves the *arguments* TermDesk builds and the state
 * it records. The kernel-side effect (turn/start honouring model + effort) is
 * documented in the app-server schema (TurnStartParams.model / .effort) rather
 * than asserted here.
 *
 *   node tools/chat-config-test.js
 */
import { ChatManager } from '../src/chat.js';

let passes = 0;
let failures = 0;
function check(name, ok, detail = '') {
  if (ok) { passes += 1; console.log(`  PASS  ${name}${detail ? ' :: ' + detail : ''}`); }
  else { failures += 1; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
}

const m = new ChatManager();

// --- create carries effort ------------------------------------------------
{
  const r = m.create({ cwd: process.cwd(), engine: 'codex', model: 'gpt-5', effort: 'high' });
  check('create returns ok', r.ok === true, r.message ?? '');
  check('summary exposes effort', r.chat.effort === 'high', String(r.chat.effort));
  check('summary exposes model', r.chat.model === 'gpt-5', String(r.chat.model));
}

// --- setConfig updates sticky state and says so in the transcript ---------
{
  const r = m.create({ cwd: process.cwd(), engine: 'codex' });
  const id = r.chat.id;
  check('new chat starts without effort', r.chat.effort === null, String(r.chat.effort));

  const set = m.setConfig(id, { model: 'deepseek-v4-pro', effort: 'max' });
  check('setConfig ok', set.ok === true, set.message ?? '');
  check('setConfig records model', set.chat.model === 'deepseek-v4-pro', String(set.chat.model));
  check('setConfig records effort', set.chat.effort === 'max', String(set.chat.effort));

  const chat = m.chats.get(id);
  const line = chat.events[chat.events.length - 1];
  check('transcript states the switch', line?.kind === 'local' && /max/.test(line.text), line?.text ?? '');

  const cleared = m.setConfig(id, { model: '', effort: '' });
  check('empty string clears model', cleared.chat.model === null, String(cleared.chat.model));
  check('empty string clears effort', cleared.chat.effort === null, String(cleared.chat.effort));

  check('unknown chat refused', m.setConfig('nope', { model: 'x' }).code === 'no_chat');
}

// --- send() adopts the override and passes it into turn/start -------------
{
  const r = m.create({ cwd: process.cwd(), engine: 'codex' });
  const id = r.chat.id;
  const chat = m.chats.get(id);
  chat.threadId = 'thread-stub';

  let captured = null;
  const stub = {
    resumeThread: async () => ({}),
    startTurn: async (threadId, text, params) => { captured = params; return { turn: { id: 'turn-1' } }; },
  };
  m.codexServer = () => stub;

  const res = await m.send(id, 'hello', { model: 'gpt-6-sol', effort: 'xhigh' });
  check('send accepted', res.ok === true, res.message ?? '');
  check('turn/start got the model', captured?.model === 'gpt-6-sol', String(captured?.model));
  check('turn/start got the effort', captured?.effort === 'xhigh', String(captured?.effort));

  captured = null;
  // The stub never completes a turn, so the chat is still marked running; a
  // real completion would clear it. Reset it to prove stickiness, not busy-ness.
  chat.status = 'idle';
  const second = await m.send(id, 'again');
  check('second turn accepted', second.ok === true, second.message ?? '');
  check('override is sticky for the next turn', captured?.effort === 'xhigh', String(captured?.effort));
  check('chat state matches', chat.model === 'gpt-6-sol' && chat.effort === 'xhigh',
    `${chat.model}/${chat.effort}`);
}

clearInterval(m.reaper);
console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
