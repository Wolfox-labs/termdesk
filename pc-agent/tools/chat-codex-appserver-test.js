/**
 * Codex 通道集成测试：直接驱动 ChatManager（等价于手机的 chat.* 调用序列）。
 *
 *   node tools/chat-codex-appserver-test.js
 *
 * create -> send(真实回合) -> read -> resume(打开历史) -> cancel -> close
 * 会创建真实会话，结束时归档。
 */
import { ChatManager } from '../src/chat.js';

const CWD = process.env.TERMDESK_TEST_CWD || process.cwd();
let pass = 0, fail = 0;
const check = (name, ok, extra = '') => { if (ok) { pass++; console.log(`  ✅ ${name}${extra ? ' — ' + extra : ''}`); } else { fail++; console.log(`  ❌ ${name}${extra ? ' — ' + extra : ''}`); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const chats = new ChatManager();
const frames = [];
chats.attach((payload) => frames.push(payload));
const waitTurn = (chatId, ms = 120000) => new Promise((resolve) => {
  const t0 = Date.now();
  const timer = setInterval(() => {
    const f = frames.filter((x) => x.event === 'chat.turn' && x.chatId === chatId).pop();
    if (f && f.state !== 'started') { clearInterval(timer); resolve(f.state); return; }
    if (Date.now() - t0 > ms) { clearInterval(timer); resolve('timeout'); }
  }, 200);
});

const main = async () => {
  console.log('== 1. chat.create（engine=codex）==');
  const created = await chats.create({ engine: 'codex', cwd: CWD, title: 'termdesk-codex-test' });
  check('创建会话', created.ok === true, created.ok ? created.chat.id : JSON.stringify(created));
  const chatId = created.chat.id;
  check('新建时还没有原生 thread', created.chat.threadId === null, `threadId=${created.chat.threadId}`);

  console.log('\n== 2. chat.send（真实回合，走 app-server）==');
  const sent = await chats.send(chatId, 'Reply with exactly: TERMDESK_OK');
  check('send 被接受', sent.ok === true, JSON.stringify({ code: sent.code, sessionId: sent.sessionId }));
  const state1 = await waitTurn(chatId);
  check('回合结束', state1 === 'ended', `state=${state1}`);
  const after = chats.get(chatId);
  check('已获得内核原生 thread id', Boolean(after.threadId), after.threadId ?? '');
  const kinds = {}; for (const e of after.events) kinds[e.kind] = (kinds[e.kind] ?? 0) + 1;
  check('转录里有内容', Object.keys(kinds).length > 0, JSON.stringify(kinds));
  const answer = after.events.filter((e) => e.kind === 'message' && e.role === 'assistant').map((e) => e.text).join(' | ');
  check('拿到助手回复', /TERMDESK_OK/.test(answer), JSON.stringify(answer.slice(0, 80)));
  check('流式预览已被权威消息替换', after.previews.length === 0, `previews=${after.previews.length}`);
  const userLines = after.events.filter((e) => e.kind === 'message' && e.role === 'user');
  check('用户消息只出现一次（乐观回显已与内核回显核对）', userLines.length === 1, userLines.length + ' 条');
  const turnFrames = frames.filter((f) => f.event === 'chat.turn' && f.chatId === chatId).map((f) => f.state);
  check('电话侧收到 chat.turn 生命周期', turnFrames.includes('ended'), turnFrames.join(','));

  console.log('\n== 3. chat.cancel（打断进行中的回合）==');
  const running = await chats.send(chatId, 'Write a 500-word essay about pencil history. Do not summarize.');
  check('第二次 send 被接受', running.ok === true);
  await sleep(1500);
  const cancelled = chats.cancel(chatId);
  check('cancel 被接受', cancelled.ok === true, JSON.stringify(cancelled));
  const state2 = await waitTurn(chatId);
  check('回合被结束', ['ended', 'cancelled', 'failed'].includes(state2), `state=${state2}`);

  console.log('\n== 4. chat.resume（打开历史 = 同一路径）==');
  const threadId = chats.get(chatId).threadId;
  chats.close(chatId);
  const resumed = await chats.resume({ engine: 'codex', id: threadId });
  check('resume 成功', resumed.ok === true, resumed.ok ? resumed.chat.id : JSON.stringify(resumed));
  if (resumed.ok) {
    const rk = {}; for (const e of resumed.chat.events) rk[e.kind] = (rk[e.kind] ?? 0) + 1;
    check('历史正文来自内核（不是重新喂文本）', resumed.chat.events.length > 0, `${resumed.chat.events.length} 条 ${JSON.stringify(rk)}`);
    check('同一原生会话身份', resumed.chat.threadId === threadId, resumed.chat.threadId);
    const again = [...chats.chats.values()].find((c) => c.sessionId === threadId);
    check('重复 resume 复用同一 chat', Boolean(again));
    chats.close(resumed.chat.id);
  }

  console.log('\n== 5. 清理 ==');
  try { await chats.codexServer().call('thread/archive', { threadId }); check('已归档测试会话', true, String(threadId).slice(0, 8)); }
  catch (e) { check('归档测试会话', false, e.message.slice(0, 80)); }
  chats.disposeAll();
  console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
  process.exit(fail === 0 ? 0 : 1);
};
main().catch((e) => { console.error('异常:', e?.stack ?? e); chats.disposeAll(); process.exit(1); });

