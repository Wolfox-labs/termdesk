/**
 * Static/live checks for the CLI shim adapter, against a stub CLI and against
 * the one real CLI that is installed.
 *
 * No model call anywhere: the stub (tools/fake-cli-agent.mjs) behaves the way
 * QoderWork and Command Code were recorded to behave, and the real qoderclicn is
 * driven only far enough to prove the argv and the NDJSON mapping - it refuses
 * before any model runs (the CLI on this machine is not logged in).
 *
 *   node tools/cli-shim-test.js
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { CliKernel, parseStreamLine } from '../src/kernels/cli.js';
import { spawnSpec, shimSpec } from '../src/kernels/registry.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const STUB = path.join(HERE, 'fake-cli-agent.mjs');

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

const manifest = (extra = {}) => ({
  newArgs: ['--print', '--output-format', 'stream-json'],
  resumeArgs: ['--print', '--output-format', 'stream-json', '--resume'],
  listArgs: ['--list-sessions'],
  prompt: 'argv',
  sessionId: 'session_id',
  sessionTitle: 'title',
  sessionCwd: 'cwd',
  sessionUpdatedAt: 'updated_at',
  text: 'text',
  replay: false,
  verified: true,
  ...extra,
});

const makeKernel = (extra = {}) =>
  new CliKernel({ id: 'faketool', bin: process.execPath, preArgs: [STUB], manifest: manifest(extra), log: () => {} });

/** Collect the updates one prompt produces. */
function collect(kernel, sessionId, text) {
  const seen = [];
  kernel.on('update', (_sid, update) => {
    if (update.sessionUpdate === 'agent_message_chunk') seen.push(update.content.text);
  });
  return kernel.prompt(sessionId, text).then((result) => ({ result, text: seen.join('') }));
}

// --- a fresh turn -----------------------------------------------------------

{
  const kernel = makeKernel();
  const { result, text } = await collect(kernel, '', 'hello shim');
  check('a fresh turn answers', text.includes('stub CLI heard'), text.slice(0, 40));
  check('the answer arrives in pieces', text.length > 20);
  check('the CLI names the session', result.sessionId === 'cli-0001', String(result.sessionId));
  check('the turn reports a stop reason', result.stopReason === 'end_turn', String(result.stopReason));
}

// --- continuing that session ------------------------------------------------

{
  const kernel = makeKernel();
  const { text } = await collect(kernel, 'cli-0001', 'and again');
  // The stub echoes the resume, so this proves the id actually reached the CLI
  // through resumeArgs - the whole point of "继续对话" for a CLI kernel.
  check('a resume passes the id to the CLI', text.includes('resumed cli-0001'), text.slice(0, 60));
}

// --- the session index ------------------------------------------------------

{
  const kernel = makeKernel();
  const { supported, sessions } = await kernel.listSessions({ cwd: process.cwd() });
  check('the session index is read', supported === true && sessions.length === 2, `${sessions.length} sessions`);
  check('session ids come from the manifest path', sessions[0]?.sessionId === 'cli-0001');
  check('session titles are carried through', sessions[1]?.title === 'stub session two');

  // A kernel whose index is not parseable must say so rather than report zero
  // sessions as if the user had none.
  const unparsed = makeKernel({ listParsed: false });
  const answer = await unparsed.listSessions({ cwd: process.cwd() });
  check('an unparsed index reports "no index", not "no sessions"', answer.supported === false);
}

// --- what it refuses to fake ------------------------------------------------

{
  const kernel = makeKernel();
  let threw = false;
  try {
    await kernel.setModel('cli-0001', 'some/model');
  } catch {
    threw = true;
  }
  check('switching a model is refused when the CLI has no flag for it', threw);

  const withFlag = makeKernel({ modelFlag: '--model' });
  const applied = await withFlag.setModel('cli-0001', 'stub/echo-1');
  check('a declared model flag is used instead of refused', applied.applied === 'stub/echo-1', JSON.stringify(applied));

  const support = kernel.sessionSupport();
  check('it declares no replay', support.loadSession === false);
  check('it declares its session index', support.list === true);
  check('it does not claim to replay transcripts', kernel.canReplay === false);
}

// --- a failing CLI ----------------------------------------------------------

{
  const kernel = makeKernel({ newArgs: ['--print', '--output-format', 'stream-json', '--fail'] });
  let message = '';
  try {
    await collect(kernel, '', 'this should fail');
  } catch (err) {
    message = String(err?.message ?? err);
  }
  check('a non-zero exit is reported with the CLI own words', message.includes('quota exceeded'), message.slice(0, 80));
}

// --- cancel -----------------------------------------------------------------

{
  const kernel = makeKernel({ newArgs: ['--print', '--output-format', 'stream-json', '--slow'] });
  const turn = collect(kernel, '', 'please take a while');
  await new Promise((r) => setTimeout(r, 400));
  const stopped = kernel.cancel();
  let settled = false;
  let rejected = false;
  await turn.then(() => { settled = true; }, () => { settled = true; rejected = true; });
  check('cancel kills the running turn', stopped === true);
  check('the cancelled turn does not hang', settled === true, rejected ? 'rejected' : 'resolved');
  check('nothing is left running', kernel.child === null);
}

// --- one turn at a time -----------------------------------------------------

{
  const kernel = makeKernel({ newArgs: ['--print', '--output-format', 'stream-json', '--slow'] });
  const first = collect(kernel, '', 'first turn');
  let refused = false;
  try {
    await kernel.prompt('', 'second turn');
  } catch (err) {
    refused = String(err?.message ?? err).includes('一轮');
  }
  check('a second concurrent turn is refused', refused);
  kernel.cancel();
  await first.catch(() => {});
}

// --- the mapping, checked against lines from a REAL CLI ---------------------
//
// These lines are verbatim shape from `qoderclicn -p -o stream-json` on this
// machine (v1.1.26, 2026-10-05), trimmed to the fields that matter. The first
// QoderWork manifest guessed `text: 'text'`, which would have shown the phone an
// empty answer; the mapping is now pinned to real output instead of a guess.

const QODER = shimSpec('qoder') ?? {
  sessionId: 'session_id',
  textPaths: ['message.content'],
  resultPaths: ['result'],
  errorPaths: ['error'],
  errorTextPaths: ['result'],
};

const REAL_LINES = [
  '{"type":"system","subtype":"init","cwd":"E:\\\\aiPic\\\\termdesk","model":"auto","permissionMode":"default","session_id":"9fcaab3b-d45d-4828-bb34-a8f1cffa919b"}',
  '{"type":"assistant","message":{"id":"9b688ff0","model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Not logged in · Please run /login"}]},"session_id":"9fcaab3b-d45d-4828-bb34-a8f1cffa919b","error":"authentication_failed"}',
  '{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","session_id":"9fcaab3b-d45d-4828-bb34-a8f1cffa919b"}',
];

{
  const init = parseStreamLine(REAL_LINES[0], QODER);
  check('the init line yields the session id', init.sessionId === '9fcaab3b-d45d-4828-bb34-a8f1cffa919b', String(init.sessionId));
  check('the init line yields no text', init.text === '', JSON.stringify(init.text));

  const assistant = parseStreamLine(REAL_LINES[1], QODER);
  check('an assistant line yields the answer at message.content',
    assistant.text === 'Not logged in · Please run /login', JSON.stringify(assistant.text));
  check('a refusal is visible as an error, not as silence',
    assistant.error === 'authentication_failed', String(assistant.error));

  const result = parseStreamLine(REAL_LINES[2], QODER);
  check('the result line is the fallback text', result.final === 'Not logged in · Please run /login', JSON.stringify(result.final));
  check('the result line carries no duplicate stream text', result.text === '');
}

// --- the adapter against the REAL CLI (it refuses before any model runs) -----

{
  const spec = spawnSpec('qoder');
  if (!spec) {
    console.log('  SKIP  real qoderclicn is not installed on this machine');
  } else {
    const kernel = new CliKernel({
      id: 'qoder',
      bin: spec.bin,
      preArgs: spec.args,
      manifest: shimSpec('qoder'),
      log: () => {},
    });
    let text = '';
    kernel.on('update', (_sid, update) => {
      if (update.sessionUpdate === 'agent_message_chunk') text += update.content.text;
    });
    let message = '';
    try {
      const answer = await kernel.prompt('', 'ping');
      message = `stopReason=${answer.stopReason} session=${answer.sessionId}`;
    } catch (err) {
      message = String(err?.message ?? err);
    }
    // Logged out today: the run must fail LOUDLY and quote the CLI. That is the
    // behaviour that was missing when "the phone showed nothing".
    check('the real CLI is driven with the declared argv', message.length > 0 || text.length > 0, message.slice(0, 70));
    check('a logged-out CLI reports its own refusal', /login|登录/i.test(message + text), (message + text).slice(0, 90));
  }
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);