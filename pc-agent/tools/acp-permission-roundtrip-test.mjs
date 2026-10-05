/** Does an answered permission actually reach the kernel? */
import { AcpKernel } from '../src/kernels/acp.js';
const kernel = new AcpKernel({
  id: 'stub',
  bin: process.execPath,
  args: ['E:/aiPic/termdesk/pc-agent/tools/fake-acp-agent.mjs'],
  log: () => {},
});
const updates = [];
kernel.on('update', (_sid, u) => updates.push(u));
kernel.on('permission', (info) => {
  console.log('permission asked:', JSON.stringify(info.options.map((o) => o.optionId)), 'default=', info.defaultOptionId);
  const ok = info.respond('allow_once');
  console.log('respond(allow_once) ->', ok);
});
await kernel.ensureStarted();
const sid = await kernel.newSession({ cwd: 'E:/aiPic/termdesk' });
console.log('session', sid, 'models:', kernel.availableModels(sid).models.length);
const res = await kernel.prompt(sid, 'hi');
console.log('prompt result:', JSON.stringify(res));
const text = updates.map((u) => u.content?.text ?? '').join('');
console.log('assistant text:', JSON.stringify(text.slice(0, 90)));
const toolResult = updates.filter((u) => u.sessionUpdate === 'tool_call_update').map((u) => u.content?.[0]?.content?.text ?? '');
console.log('tool result:', JSON.stringify(toolResult));
kernel.dispose();
process.exit(0);