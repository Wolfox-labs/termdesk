/**
 * P4 engine checks, driven through the real WebSocket protocol.
 *
 * NOTE: this suite exercises the deprecated `ai.*` task pipeline, kept for
 * wire compatibility. The product conversation path is `chat.*` with
 * `engine: 'codex' | 'dsh'` — see tools/chat-e2e.js and tools/chat-engine-test.js.
 *
 * These invoke live AI engines, so they are slower than the other suites and
 * cost real tokens. The Codex cases are the important ones: multi-turn resume
 * and per-command progress events are exactly what the phone UI relies on.
 *
 *   node tools/engines-test.js            # all checks
 *   node tools/engines-test.js --fast     # skip the slow sandbox case
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = Number(process.env.TERMDESK_TEST_PORT || 7461);
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();
const FAST = process.argv.includes('--fast');

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const agent = spawn(process.execPath, [AGENT, '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  stdio: ['ignore', 'pipe', 'pipe'],
});
agent.stderr.on('data', (d) => process.stderr.write(`[agent] ${d}`));
await new Promise((r) => setTimeout(r, 2500));

const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
const frames = [];
ws.on('message', (raw) => frames.push(JSON.parse(raw.toString())));

const send = (o) => ws.send(JSON.stringify(o));
const waitFor = async (fn, ms = 300000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 150));
  }
  return null;
};
const find = (type) => frames.filter((f) => f.type === type);
const taskOf = (id) => find('ai.task').filter((f) => f.task?.id === id).pop()?.task;

await new Promise((r) => ws.on('open', () => {
  send({ type: 'auth', token });
  setTimeout(r, 800);
}));

try {
  // --- engine discovery ---------------------------------------------------
  send({ type: 'ai.engines' });
  const eng = await waitFor(() => find('ai.engines')[0]);
  const ids = (eng?.engines ?? []).map((e) => e.id);
  check('reports available engines', ids.includes('codex') && ids.includes('dsh'), ids.join(','));
  const codexInfo = eng?.engines?.find((e) => e.id === 'codex');
  const dshInfo = eng?.engines?.find((e) => e.id === 'dsh');
  check('codex engine is present on this machine', codexInfo?.available === true, codexInfo?.path);
  check('dsh engine is present on this machine', dshInfo?.available === true, dshInfo?.path);
  check('engine capabilities are declared', codexInfo?.multiTurn === true && dshInfo?.multiTurn === false,
    `codex.multiTurn=${codexInfo?.multiTurn} dsh.multiTurn=${dshInfo?.multiTurn}`);

  // --- validation ---------------------------------------------------------
  send({ type: 'ai.submit', engine: 'nope', prompt: 'x' });
  const badEng = await waitFor(() => find('action.result').find((f) => f.action === 'ai.submit'));
  check('rejects an unknown engine', badEng?.ok === false && badEng?.code === 'bad_engine', badEng?.message);

  send({ type: 'ai.submit', engine: 'codex', prompt: '   ' });
  const empty = await waitFor(() =>
    find('action.result').find((f) => f.action === 'ai.submit' && f.code === 'empty_prompt'));
  check('rejects an empty prompt', empty?.ok === false && empty?.code === 'empty_prompt', empty?.message);

  send({ type: 'ai.cancel', taskId: 't999' });
  const noTask = await waitFor(() =>
    find('action.result').find((f) => f.action === 'ai.cancel'));
  check('reports cancelling a non-running task', noTask?.ok === false, noTask?.message);

  send({ type: 'ai.reset', engine: 'bogus' });
  const badReset = await waitFor(() => find('action.result').find((f) => f.action === 'ai.reset'));
  check('rejects resetting an unknown engine', badReset?.ok === false, badReset?.message);

  // --- Codex: real task with tool use -------------------------------------
  console.log('\n  (running a live Codex task; may take a minute)');
  const before = frames.length;
  send({
    type: 'ai.submit',
    engine: 'codex',
    prompt: 'Run the shell command "echo TERMDESK_E2E" and then reply with exactly: TASK_DONE',
    cwd: path.join(os.homedir()),
  });
  const started = await waitFor(() => find('ai.started').slice(-1)[0]);
  const taskId = started?.taskId;
  check('accepts a Codex task', Boolean(taskId), taskId);

  const finished = await waitFor(
    () => find('ai.finished').find((f) => f.taskId === taskId),
    300000,
  );
  check('Codex task finishes', finished?.status === 'completed', `status=${finished?.status} exit=${finished?.exitCode}`);

  // Progress events during the run are the point of this engine.
  const events = find('ai.event').filter((f) => f.taskId === taskId);
  check('emits progress events while running', events.length > 0, `${events.length} events`);

  send({ type: 'ai.task', taskId });
  const detail = await waitFor(() => find('ai.task').find((f) => f.task?.id === taskId));
  const t = detail?.task;
  check('records the conversation thread id', typeof t?.threadId === 'string' && t.threadId.length > 10,
    t?.threadId?.slice(0, 8));
  check('captured the final answer', (t?.finalText ?? '').includes('TASK_DONE'),
    JSON.stringify((t?.finalText ?? '').slice(0, 60)));

  // The first `command` event is the item.started frame, whose exit code is
  // legitimately null while the command is still running. Assert on the
  // completed one.
  const cmdEvent = t?.events?.find((e) => e.kind === 'command' && e.state === 'completed');
  const cmdStarted = t?.events?.find((e) => e.kind === 'command' && e.state === 'running');
  check('recorded the command it executed', Boolean(cmdEvent), cmdEvent?.text?.slice(0, 60));
  check('reported the command as running before completing', Boolean(cmdStarted));
  check('recorded the command exit code', cmdEvent?.exitCode === 0, `exit=${cmdEvent?.exitCode}`);
  check('event stream never exceeds its cap', (t?.events?.length ?? 0) <= 400, `${t?.events?.length} events`);

  // Regression: the engine manager originally reported its own name in a `type`
  // field, and encodeFrame's payload spread silently overwrote the frame type —
  // so ai.finished arrived as `task.finished` and no client ever saw a task end.
  // Assert every frame on the wire uses a declared protocol type.
  const validTypes = new Set([
    'ai.started', 'ai.event', 'ai.finished', 'ai.engines', 'ai.tasks', 'ai.task',
    'action.result', 'error', 'status', 'procs', 'services', 'term.list',
    'fs.listing', 'fs.file', 'fs.written', 'fs.roots', 'term.opened', 'term.output',
    'term.exit', 'codex.config', 'auth.ok', 'hello', 'pong',
  ]);
  const unexpected = [...new Set(frames.map((f) => f.type))].filter((ty) => !validTypes.has(ty));
  check('every frame uses a declared protocol type', unexpected.length === 0,
    unexpected.length ? `unexpected: ${unexpected.join(', ')}` : `${frames.length} frames checked`);

  // --- Codex: multi-turn continuity ---------------------------------------
  console.log('\n  (verifying multi-turn resume)');
  // Capture how many starts we have already seen. `find('ai.started')` keeps
  // every frame ever received, so taking the last one before the new task
  // actually starts would return the PREVIOUS task's id.
  const startsBefore = find('ai.started').length;
  send({
    type: 'ai.submit',
    engine: 'codex',
    prompt: 'What was the exact command I just asked you to run? Reply with just the command.',
    resume: true,
  });
  const followId = await waitFor(() => {
    const all = find('ai.started');
    return all.length > startsBefore ? all[all.length - 1].taskId : null;
  });
  check('follow-up task was accepted', Boolean(followId), followId);
  const followDone = await waitFor(
    () => find('ai.finished').filter((f) => f.taskId === followId)[0],
    300000,
  );
  check('follow-up task finishes', followDone?.status === 'completed', `status=${followDone?.status}`);

  send({ type: 'ai.task', taskId: followId });
  const followDetail = await waitFor(() =>
    find('ai.task').filter((f) => f.task?.id === followId).pop());
  const followText = followDetail?.task?.finalText ?? '';
  check('follow-up remembers the earlier turn', /TERMDESK_E2E/i.test(followText),
    JSON.stringify(followText.slice(0, 80)));

  // --- task list ----------------------------------------------------------
  send({ type: 'ai.tasks' });
  const list = await waitFor(() => find('ai.tasks')[0]);
  check('lists submitted tasks', (list?.tasks?.length ?? 0) >= 2, `${list?.tasks?.length} tasks`);
  check('task list is newest-first', (list?.tasks?.[0]?.startedAt ?? 0) >= (list?.tasks?.[1]?.startedAt ?? 0));

  if (!FAST) {
    // --- DSH: one-shot engine --------------------------------------------
    console.log('\n  (running a live DSH task; may take a minute)');
    // Scope to frames that arrive AFTER this submit. An unscoped "last
    // ai.started" is satisfied instantly by the earlier Codex task's frame, so
    // the DSH assertions would silently run against the wrong task.
    const dshBefore = frames.length;
    send({
      type: 'ai.submit',
      engine: 'dsh',
      prompt: 'Reply with exactly: DSH_TASK_DONE',
      cwd: path.join(os.homedir()),
    });
    const dshStart = await waitFor(
      () => frames.slice(dshBefore).find((f) => f.type === 'ai.started' && f.engine === 'dsh'),
    );
    const dshId = dshStart?.taskId;
    check('accepts a DSH task', dshStart?.engine === 'dsh', dshId);

    const dshDone = await waitFor(() => find('ai.finished').find((f) => f.taskId === dshId), 300000);
    check('DSH task finishes', dshDone?.status === 'completed', `status=${dshDone?.status}`);

    const dshDetailBefore = frames.length;
    send({ type: 'ai.task', taskId: dshId });
    const dshDetail = await waitFor(
      () => frames.slice(dshDetailBefore).find((f) => f.type === 'ai.task' && f.task?.id === dshId),
    );
    const dshText = dshDetail?.task?.finalText ?? '';
    check('DSH answer has no launcher banner', !dshText.includes('[ctrl-immune]'),
      JSON.stringify(dshText.slice(0, 60)));
    check('DSH answer captured', dshText.includes('DSH_TASK_DONE'), JSON.stringify(dshText.slice(0, 60)));
  } else {
    console.log('SKIP  DSH live task (--fast)');
  }

  // --- cancellation -------------------------------------------------------
  console.log('\n  (verifying cancellation)');
  const startsBeforeCancel = find('ai.started').length;
  send({ type: 'ai.submit', engine: 'codex', prompt: 'Count slowly from 1 to 500, one per line.' });
  const cancelId = await waitFor(() => {
    const all = find('ai.started');
    return all.length > startsBeforeCancel ? all[all.length - 1].taskId : null;
  });
  check('long task was accepted', Boolean(cancelId), cancelId);
  // Give it a moment to actually begin, so cancellation has something to stop.
  await waitFor(() => find('ai.event').length > 0, 20000);
  send({ type: 'ai.cancel', taskId: cancelId });
  const cancelled = await waitFor(() => find('ai.finished').find((f) => f.taskId === cancelId), 60000);
  check('cancels a running task', cancelled?.status === 'cancelled', `status=${cancelled?.status}`);

  send({ type: 'ai.task', taskId: cancelId });
  const cancelledDetail = await waitFor(() => find('ai.task').filter((f) => f.task?.id === cancelId).pop());
  check('cancelled task is marked cancelled', cancelledDetail?.task?.status === 'cancelled',
    cancelledDetail?.task?.status);

  // --- session reset ------------------------------------------------------
  send({ type: 'ai.reset', engine: 'codex' });
  const reset = await waitFor(() => find('action.result').find((f) => f.action === 'ai.reset' && f.ok === true));
  check('resets the Codex conversation', reset?.ok === true, reset?.message);
} catch (err) {
  check('engine test harness completed', false, err.message);
}

ws.close();
agent.kill('SIGKILL');

const failures = results.filter((r) => !r.passed).length;
console.log(`\nP4: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 400);
