/**
 * Which models does a kernel's new session offer? Metadata only - no model call.
 *
 * Written because the phone's picker could not show ACP models at all, so the
 * only way to know the exact id to pin (and to see that a picker is possible)
 * was to ask the kernel. Prints the ids that match a filter.
 *
 *   node tools/acp-model-list.mjs opencode deepseek
 *   node tools/acp-model-list.mjs mimo
 */
import fs from 'node:fs';
import path from 'node:path';
import WebSocket from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const [engine = 'opencode', filter = ''] = process.argv.slice(2);
const port = process.env.TERMDESK_PORT ?? '7421';
const token = machineTokenOrSkip('acp-model-list');

const ws = new WebSocket(`ws://127.0.0.1:${port}`);
const finish = (code) => { try { ws.close(); } catch {} setTimeout(() => process.exit(code), 200); };

ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
ws.on('message', (raw) => {
  const f = JSON.parse(raw.toString());
  if (f.type === 'auth.ok') {
    ws.send(JSON.stringify({ type: 'chat.create', engine, cwd: process.env.TERMDESK_CWD || process.cwd() }));
    return;
  }
  if (f.type === 'chat' && f.id) {
    const models = f.models?.models ?? [];
    const re = filter ? new RegExp(filter, 'i') : null;
    const hits = models.filter((m) => !re || re.test(JSON.stringify(m)));
    console.log(`kernel=${engine} session models=${models.length} matching=${hits.length}`);
    for (const m of hits.slice(0, 40)) console.log(`  ${m.modelId ?? m.id ?? '?'}  ${m.name ?? ''}`);
    ws.send(JSON.stringify({ type: 'chat.close', chatId: f.id }));
    finish(0);
    return;
  }
  if (f.type === 'error') { console.error('error:', JSON.stringify(f)); finish(1); }
});
setTimeout(() => { console.error('timeout'); finish(2); }, 60000);
