/**
 * 协议级端到端：手机协议 → PC agent → Codex app-server。
 *
 *   node tools/codex-ws-e2e.js
 *
 * 用真实 WebSocket + 真实令牌，模拟手机 App 的调用序列：
 *   auth → sessions.list → sessions.read → chat.create → chat.send → chat.resume → chat.close
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { CodexAppServer } from '../src/kernels/codex.js';

const URL = process.env.TERMDESK_WS || 'ws://127.0.0.1:7420';
const CWD = process.env.TERMDESK_TEST_CWD || process.cwd();
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

let pass = 0, fail = 0;
const check = (n, ok, extra = '') => { if (ok) { pass++; console.log(`  ✅ ${n}${extra ? ' — ' + extra : ''}`); } else { fail++; console.log(`  ❌ ${n}${extra ? ' — ' + extra : ''}`); } };

const ws = new WebSocket(URL, { maxPayload: 8 * 1024 * 1024 });
const inbox = [];
ws.on('message', (raw) => { try { inbox.push(JSON.parse(String(raw))); } catch { /* ignore */ } });
const waitFor = (pred, ms, label) => new Promise((resolve, reject) => {
  const t0 = Date.now();
  const timer = setInterval(() => {
    const hit = inbox.find(pred);
    if (hit) { clearInterval(timer); resolve(hit); return; }
    if (Date.now() - t0 > ms) { clearInterval(timer); reject(new Error(`timeout ${label}`)); }
  }, 150);
});
const sendFrame = (obj) => ws.send(JSON.stringify(obj));

const main = async () => {
  await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
  console.log('== 1. auth ==');
  sendFrame({ type: 'auth', token });
  await waitFor((f) => f.type === 'auth.ok', 8000, 'auth.ok');
  check('鉴权通过', true);

  console.log('\n== 2. sessions.list（历史列表来自内核）==');
  sendFrame({ type: 'sessions.list', engine: 'codex' });
  const listing = await waitFor((f) => f.type === 'sessions', 25000, 'sessions');
  check('codexSource 来自内核', String(listing.codexSource).startsWith('kernel'), String(listing.codexSource));
  const codex = (listing.sessions ?? []).filter((s) => s.engine === 'codex');
  check('列出原生会话', codex.length > 0, `${codex.length} 条`);
  check('条目是原生 thread id', /^[0-9a-f-]{36}$/.test(codex[0]?.id ?? ''), codex[0]?.id);
  check('带标题', Boolean(codex[0]?.title), JSON.stringify((codex[0]?.title ?? '').slice(0, 40)));

  console.log('\n== 3. sessions.read（历史正文来自内核）==');
  inbox.length = 0;
  sendFrame({ type: 'sessions.read', engine: 'codex', sessionId: codex[0].id });
  const detail = await waitFor((f) => f.type === 'session' || f.type === 'error', 40000, 'session');
  check('读到会话', detail.type === 'session', detail.code ?? '');
  if (detail.type === 'session') {
    const kinds = {}; for (const e of detail.events ?? []) kinds[e.kind] = (kinds[e.kind] ?? 0) + 1;
    check('正文事件 > 0', (detail.events ?? []).length > 0, `${detail.events.length} 条 ${JSON.stringify(kinds)}`);
    check('meta.engine=codex 且 id 一致', detail.meta?.engine === 'codex' && detail.meta?.id === codex[0].id);
  }

  console.log('\n== 4. chat.create + chat.send（真实回合）==');
  inbox.length = 0;
  sendFrame({ type: 'chat.create', engine: 'codex', cwd: CWD, title: 'termdesk-ws-e2e' });
  const created = await waitFor((f) => f.type === 'chat' && f.id && f.engine === 'codex', 15000, 'chat.create');
  const chatId = created.id;
  check('创建会话', Boolean(chatId), chatId);
  sendFrame({ type: 'chat.send', chatId, text: 'Reply with exactly: WS_OK' });
  const turn = await waitFor((f) => f.type === 'chat.turn' && f.chatId === chatId && f.state !== 'started', 120000, 'chat.turn');
  check('回合结束', turn.state === 'ended', `state=${turn.state}`);
  const answer = inbox.filter((f) => f.type === 'chat.event' && f.chatId === chatId && f.item?.kind === 'message' && f.item?.role === 'assistant').map((f) => f.item.text).join('');
  check('回复内容正确', /WS_OK/.test(answer), JSON.stringify(answer.slice(0, 60)));
  check('流式帧到达手机', inbox.some((f) => f.type === 'chat.event' && f.chatId === chatId && f.stream === true), '');

  console.log('\n== 5. chat.resume（继续对话 = 同一路径）==');
  inbox.length = 0;
  sendFrame({ type: 'chat.read', chatId });
  const chatFrame = await waitFor((f) => f.type === 'chat' && f.id === chatId, 10000, 'chat.read');
  const threadId = chatFrame.threadId;
  check('会话拿到原生 thread id', Boolean(threadId), threadId);
  sendFrame({ type: 'chat.close', chatId });
  await waitFor((f) => f.type === 'chat.closed' && f.chatId === chatId, 10000, 'chat.closed');
  inbox.length = 0;
  sendFrame({ type: 'chat.resume', engine: 'codex', sessionId: threadId });
  const resumed = await waitFor((f) => f.type === 'chat' && f.sessionId === threadId, 30000, 'chat.resume');
  check('resume 成功并复用原生身份', resumed.threadId === threadId, resumed.threadId);
  const resumedEvents = Array.isArray(resumed.events) ? resumed.events : [];
  check('历史正文随 resume 一起回来', resumedEvents.length > 0, resumedEvents.length + ' 条');

  console.log('\n== 6. 清理 ==');
  ws.close();
  // 归档测试产生的会话（标题固定，避免误伤用户会话）
  const server = new CodexAppServer({ cwd: CWD });
  try {
    const mine = (await server.listThreads({ limit: 300, archived: true }))?.data ?? [];
    const junk = mine.filter((t) => /Reply with exactly: WS_OK|termdesk-(ws-e2e|codex-test|ws-diag)/.test(t.title ?? ''));
    for (const t of junk) await server.call('thread/archive', { threadId: t.id }).catch(() => {});
    check('归档测试会话', true, `本轮 ${String(threadId).slice(0, 8)} + 历史遗留 ${junk.length} 条`);
  } catch (e) { check('归档测试会话', false, e.message.slice(0, 80)); }
  server.dispose();

  console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
  process.exit(fail === 0 ? 0 : 1);
};
main().catch((e) => { console.error('异常:', e?.stack ?? e); try { ws.close(); } catch {} process.exit(1); });



