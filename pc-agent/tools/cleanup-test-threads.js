/**
 * 归档 TermDesk 测试产生的 Codex 会话（只读元数据 + archive，**不调用模型**）。
 *
 *   node tools/cleanup-test-threads.js           # 预览
 *   node tools/cleanup-test-threads.js --apply   # 执行
 *
 * 覆盖两类：内核窗口内的会话，以及只在磁盘 rollout 里出现、但内核仍能按 id
 * 读取的会话（title 由内核提供，不解析 JSONL 内容）。只匹配测试脚本固定使用
 * 的标题/目录，避免误伤真实会话。
 */
import { CodexAppServer, discoverThreadIdsFromDisk } from '../src/kernels/codex.js';
import { sessionRoots } from '../src/sessions.js';

const APPLY = process.argv.includes('--apply');
const PATTERN = /Reply with|Reply PHONE_OK|Reply ONE_TIME|termdesk-(ws-e2e|codex-test|ws-diag|smoke|unified)|unified-smoke/i;

const server = new CodexAppServer({ cwd: process.cwd() });

const main = async () => {
  const active = (await server.listThreads({ limit: 500, archived: false }))?.data ?? [];
  const archived = new Set(((await server.listThreads({ limit: 500, archived: true }))?.data ?? []).map((t) => t.id));
  const known = new Set(active.map((t) => t.id));

  const candidates = active.map((t) => ({ id: t.id, title: t.title, cwd: t.cwd }));
  const diskIds = discoverThreadIdsFromDisk({ root: sessionRoots().codex, limit: 120 })
    .filter((id) => !known.has(id) && !archived.has(id));
  for (let i = 0; i < diskIds.length; i += 8) {
    const batch = await Promise.all(diskIds.slice(i, i + 8).map((id) => server.readThread(id, { includeTurns: false }).catch(() => null)));
    for (const th of batch) if (th?.id && !th.parentThreadId) candidates.push({ id: th.id, title: th.name || (th.preview ?? ''), cwd: th.cwd });
  }

  const junk = candidates.filter((t) => PATTERN.test((t.title ?? '').trim()) || PATTERN.test(t.cwd ?? ''));
  console.log(`候选会话 ${candidates.length} 条（内核 ${active.length} + 磁盘补 ${candidates.length - active.length}），命中测试模式 ${junk.length} 条：`);
  for (const t of junk.slice(0, 20)) console.log(`  ${t.id}  ${String(t.title ?? '').slice(0, 36)}  ${t.cwd}`);
  if (!APPLY) { console.log('\n（预览模式；加 --apply 执行归档）'); server.dispose(); process.exit(0); }
  let done = 0;
  for (const t of junk) { try { await server.call('thread/archive', { threadId: t.id }); done += 1; } catch (e) { console.log(`  失败 ${t.id}: ${e.message.slice(0, 70)}`); } }
  console.log(`\n已归档 ${done}/${junk.length} 条。`);
  server.dispose(); process.exit(0);
};
main().catch((e) => { console.error('异常:', e?.message ?? e); server.dispose(); process.exit(1); });
