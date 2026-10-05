/** Does the phone's "open a recorded ACP session" work over the wire? */
import fs from 'node:fs';
import WebSocket from 'ws';
const token = fs.readFileSync(process.env.USERPROFILE + '/.termdesk/token', 'utf8').trim();
const ws = new WebSocket('ws://127.0.0.1:7421');
let listed = null;
ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
ws.on('message', (raw) => {
  const f = JSON.parse(raw.toString());
  if (f.type === 'auth.ok') { ws.send(JSON.stringify({ type: 'sessions.list' })); return; }
  if (f.type === 'sessions') {
    const acp = (f.sessions ?? []).filter((s) => s.engine === 'mimo' || s.engine === 'opencode' || s.engine === 'stub');
    console.log('sessions:', (f.sessions ?? []).length, ' acp:', acp.length);
    for (const s of acp.slice(0, 4)) console.log(`  ${s.engine} ${s.id} canResume=${s.canResume} note=${s.resumeNote ?? '-'} cwd=${s.cwd}`);
    listed = acp[0];
    if (!listed) { console.log('no ACP session to open; creating one via the stub instead'); ws.send(JSON.stringify({ type: 'chat.create', engine: 'stub', cwd: 'E:/aiPic/termdesk' })); return; }
    console.log('-> sessions.read', listed.engine, listed.id);
    ws.send(JSON.stringify({ type: 'sessions.read', engine: listed.engine, sessionId: listed.id, path: listed.path }));
    return;
  }
  if (f.type === 'chat') { console.log('stub chat created', f.id); ws.close(); process.exit(0); return; }
  if (f.type === 'session') {
    console.log(`session frame: engine=${f.meta?.engine} canResume=${f.meta?.canResume} events=${(f.events ?? []).length}`);
    console.log('  first kinds:', (f.events ?? []).slice(0, 5).map((e) => e.kind).join(','));
    ws.close(); process.exit(0); return;
  }
  if (f.type === 'error') { console.log('ERROR frame:', JSON.stringify(f)); ws.close(); process.exit(1); return; }
  if (f.type === 'action.result') { console.log('action.result:', JSON.stringify(f).slice(0, 200)); return; }
});
setTimeout(() => { console.log('timed out'); process.exit(2); }, 40000);