/**
 * Optional UI fixture. No network, model SDK, credentials or shell execution.
 * Register explicitly as a CLI shim; never discovered as a built-in assistant.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const store = process.env.TERMDESK_UI_DEMO_STORE || path.join(path.dirname(fileURLToPath(import.meta.url)), '.ui-demo-sessions.json');
let sessions = [];
try { sessions = JSON.parse(fs.readFileSync(store, 'utf8')); } catch { /* first run */ }
if (!Array.isArray(sessions)) sessions = [];
const write = (value) => process.stdout.write(JSON.stringify(value) + '\n');
if (args.includes('--list-sessions')) {
  sessions.forEach(write);
  process.exit(0);
}
if (!args.includes('--print')) {
  process.stderr.write('UI 演示助手仅接受 --print 或 --list-sessions；不会调用模型。\n');
  process.exit(2);
}
const resumeIndex = args.indexOf('--resume');
const id = resumeIndex >= 0 ? args[resumeIndex + 1] : `ui-demo-${crypto.randomUUID()}`;
let session = sessions.find((item) => item.session_id === id);
if (resumeIndex >= 0 && !session) {
  process.stderr.write('找不到合成演示会话。请新建会话。\n');
  process.exit(3);
}
if (!session) {
  session = { session_id: id, title: '界面与交互演示（无模型）', cwd: process.cwd(), turns: 0 };
  sessions.unshift(session);
}
session.turns += 1;
session.updated_at = new Date().toISOString();
fs.writeFileSync(store, JSON.stringify(sessions.slice(0, 20), null, 2));
write({ session_id: id });
const answer = `**界面演示 · 未调用模型**\n\n这是第 ${session.turns} 次合成回复，用于检查会话界面、滚动和草稿。\n\n` +
  '### 可以体验\n\n- 返回会话列表，再次打开这段对话。\n- 输入一段草稿，离开后回来查看。\n- 到文件页编辑演示文件，再保存并离开。\n- 在终端运行 `pwd`，确认命令在哪台设备上执行。\n\n' +
  '> 本助手只生成固定演示内容，不分析项目、不执行工具，也不连接模型服务。真实工作请另行安装和配置助手。';
for (const piece of answer.match(/[\s\S]{1,20}/g) || []) {
  write({ type: 'assistant', text: piece });
  await new Promise((resolve) => setTimeout(resolve, 45));
}
write({ type: 'result', session_id: id, done: true });
