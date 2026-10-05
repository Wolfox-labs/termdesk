/** Reproduce exactly what the phone's "open a recorded session" does for an ACP kernel. */
import { ChatManager } from '../src/chat.js';
const manager = new ChatManager();
const frames = [];
manager.attach((p) => frames.push(p));
try {
  const list = await manager.listAcpSessions('mimo');
  console.log('mimo sessions from the kernel:', list.length);
  const first = list[0];
  if (!first) { console.log('no mimo session to open'); process.exit(0); }
  console.log('opening', first.id, '@', first.cwd);
  const detail = await manager.readAcpSession('mimo', first.id);
  console.log('events:', detail.events.length, 'total:', detail.totalEvents);
  for (const e of detail.events.slice(0, 6)) console.log('  ', e.kind, '|', String(e.text).slice(0, 70));
} catch (err) {
  console.log('FAILED:', String(err?.message ?? err).slice(0, 300));
}
manager.disposeAll();
process.exit(0);