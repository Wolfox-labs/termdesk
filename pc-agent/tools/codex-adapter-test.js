/**
 * Codex app-server adapter 端到端测试。
 *
 *   node tools/codex-adapter-test.js
 *
 * 覆盖：initialize / thread/list / thread/read + 事件映射 / thread/start /
 * turn/start 流式通知 / turn/interrupt / thread/archive 清理。
 * 会发起一次真实模型调用（用本机 Codex 配置里的默认模型）。
 */
import { CodexAppServer, notificationToChatEvents, turnToChatEvents } from '../src/kernels/codex.js';

const CWD = process.env.TERMDESK_TEST_CWD || process.cwd();
let pass = 0, fail = 0;
const check = (name, ok, extra = '') => { if (ok) { pass += 1; console.log(`  ✅ ${name}${extra ? ' — ' + extra : ''}`); } else { fail += 1; console.log(`  ❌ ${name}${extra ? ' — ' + extra : ''}`); } };

const codex = new CodexAppServer({ cwd: CWD });

// 收集通知，按 turn 分组
const notes = [];
let turnDone = null;
codex.on('notification', (method, params) => {
  notes.push({ method, params });
  if (method === 'turn/completed') turnDone?.(params?.turn ?? {});
});
codex.on('serverRequest', (method) => console.log(`  ⚠️  引擎请求审批/输入（未实现，已拒绝）: ${method}`));
codex.on('exit', (code) => console.log(`  ⚠️  app-server 退出 code=${code}`));

const main = async () => {
  console.log('== 1. initialize ==');
  await codex.ensureStarted();
  check('app-server 启动 + initialize 握手', true);

  console.log('\n== 2. thread/list（会话索引）==');
  const list = await codex.listThreads({ limit: 5 });
  check('返回 data 数组', Array.isArray(list?.data), `${list?.data?.length ?? 0} 条`);
  const first = list.data[0];
  check('条目带原生 id/title/cwd/updatedAt', Boolean(first?.id && first?.cwd), JSON.stringify({ id: first?.id?.slice(0, 8), cwd: first?.cwd, titleLen: (first?.title ?? '').length }));

  console.log('\n== 3. thread/read + 事件映射（历史正文）==');
  const thread = await codex.readThread(first.id, { includeTurns: true });
  check('读到 thread', Boolean(thread?.id));
  const turns = thread?.turns ?? [];
  check('thread 带 turns', turns.length > 0, `${turns.length} 轮`);
  let mapped = 0; const kinds = {};
  for (const turn of turns) for (const ev of turnToChatEvents(turn)) { mapped += 1; kinds[ev.kind] = (kinds[ev.kind] ?? 0) + 1; }
  check('turns -> chat 事件', mapped > 0, `${mapped} 条 ${JSON.stringify(kinds)}`);

  console.log('\n== 4. thread/start（新建）==');
  const started = await codex.startThread({ cwd: CWD });
  const threadId = started?.thread?.id;
  check('拿到新 thread id', Boolean(threadId), `${threadId?.slice(0, 8)} model=${started?.model} provider=${started?.modelProvider} approval=${JSON.stringify(started?.approvalPolicy)} sandbox=${JSON.stringify(started?.sandbox)}`);

  console.log('\n== 5. 一次真实回合（流式通知）==');
  notes.length = 0;
  const waited = new Promise((resolve) => { turnDone = resolve; setTimeout(() => resolve({ status: 'timeout' }), 90_000); });
  const turn = await codex.startTurn(threadId, 'Reply with exactly: TERMDESK_OK');
  check('turn/start 返回 turn', Boolean(turn?.turn?.id), `turnId=${turn?.turn?.id?.slice(0, 12)} status=${turn?.turn?.status}`);
  const finished = await waited;
  const methods = [...new Set(notes.map((n) => n.method))];
  check('收到流式通知', methods.length > 0, methods.slice(0, 8).join(', '));
  let streamed = '';
  for (const n of notes) for (const ev of notificationToChatEvents(n.method, n.params)) if (ev.kind === 'message' && ev.role === 'assistant' && ev.streaming) streamed += ev.text;
  let finalText = '';
  for (const n of notes) for (const ev of notificationToChatEvents(n.method, n.params)) if (ev.kind === 'message' && ev.role === 'assistant' && !ev.streaming && !ev.meta) finalText = ev.text;
  check('回合正常结束', finished?.status === 'completed', `status=${finished?.status}`);
  check('拿到助手回复', Boolean(finalText || streamed), JSON.stringify((finalText || streamed).slice(0, 60)));
  const turnEnd = notes.filter((n) => n.method === 'turn/completed').length;
  check('收到 turn/completed', turnEnd > 0);

  console.log('\n== 6. thread/resume（继续对话）==');
  const resumed = await codex.resumeThread(threadId, { cwd: CWD });
  check('resume 返回同一 thread', resumed?.thread?.id === threadId, `${resumed?.thread?.id?.slice(0, 8)}`);

  console.log('\n== 7. turn/interrupt（打断）==');
  notes.length = 0;
  const second = new Promise((resolve) => { turnDone = resolve; setTimeout(() => resolve({ status: 'timeout' }), 60_000); });
  // Interrupt as soon as the kernel reports the turn started, so the interrupt
  // cannot race the turn's completion.
  let interruptOutcome = null;
  const onNote = (method) => {
    if (method !== 'turn/started' || interruptOutcome) return;
    interruptOutcome = codex.interruptTurn(threadId)
      .then(() => 'accepted')
      .catch((e) => 'error: ' + e.message);
  };
  codex.on('notification', onNote);
  await codex.startTurn(threadId, 'Write a detailed 500-word essay about the history of the pencil. Do not stop early and do not summarize.');
  const outcome = await second;
  codex.off('notification', onNote);
  const resolved = interruptOutcome ? await interruptOutcome : 'never fired';
  check('interrupt 被内核接受', resolved === 'accepted', resolved);
  check('打断后回合状态为 interrupted', outcome?.status === 'interrupted', `status=${outcome?.status}`);

  console.log('\n== 8. 清理 ==');
  try { await codex.call('thread/archive', { threadId }); check('已归档测试会话', true, threadId.slice(0, 8)); }
  catch (e) { check('归档测试会话', false, e.message.slice(0, 80)); }

  codex.dispose();
  console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
  process.exit(fail === 0 ? 0 : 1);
};

main().catch((err) => { console.error('测试异常:', err?.stack ?? err); codex.dispose(); process.exit(1); });




