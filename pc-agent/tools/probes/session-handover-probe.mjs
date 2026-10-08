/**
 * Ask a RUNNING agent for the same recorded session twice, and print what it answers.
 *
 * This exists because a screenshot could not settle a question: after taking over a
 * session, reopening it from a second device (or the same one after a restart) showed no
 * handover note on the phone, and the code looked like it should send one. A screenshot
 * cannot tell "the PC sent nothing" apart from "the phone drew nothing", so this asks the
 * PC directly.
 *
 * Read-only: `chat.resume` attaches a kernel to a session the agent already had, and it
 * never sends a prompt, so it costs no model call.
 *
 *   node tools/probes/session-handover-probe.mjs [engine] [sessionId]
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const port = process.env.TERMDESK_PORT ?? '7420';
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();
const engine = process.argv[2] ?? 'codex';
const sessionId = process.argv[3] ?? '';

if (!sessionId) {
  console.error('usage: node tools/probes/session-handover-probe.mjs <engine> <sessionId>');
  process.exit(2);
}

const ws = new WebSocket(`ws://127.0.0.1:${port}`);
const interesting = ['error', 'action.result', 'chat', 'chat.closed'];
let frames = 0;

ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
ws.on('message', (raw) => {
  const frame = JSON.parse(raw.toString());
  frames += 1;
  if (!interesting.includes(frame.type)) return;
  const brief = { ...frame };
  if (brief.type === 'chat' && brief.chat) {
    brief.chat = { id: brief.chat.id, engine: brief.chat.engine, sessionId: brief.chat.sessionId, status: brief.chat.status };
  }
  console.log('  <-', JSON.stringify(brief).slice(0, 400));
});
ws.on('error', (err) => console.error('ws error:', err.message));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await new Promise((r) => ws.on('open', r));
await sleep(400);

console.log(`first  chat.resume ${engine} / ${sessionId}`);
ws.send(JSON.stringify({ type: 'chat.resume', engine, sessionId }));
await sleep(6000);

console.log(`second chat.resume ${engine} / ${sessionId}  (the "another device" case)`);
ws.send(JSON.stringify({ type: 'chat.resume', engine, sessionId }));
await sleep(6000);

console.log(`total frames: ${frames}`);
ws.close();
process.exit(0);
