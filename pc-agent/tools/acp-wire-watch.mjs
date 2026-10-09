/**
 * Scripted client: exactly what the phone does, minus the phone.
 *
 * Creates an ACP chat, sends one prompt, and prints EVERY frame the agent sends
 * back. This is how we find where a stream stops: between the kernel and the
 * agent, or between the agent and the phone.
 */
import fs from 'node:fs';
import WebSocket from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const token = machineTokenOrSkip('acp-wire-watch');
const url = 'ws://127.0.0.1:7421';
const ws = new WebSocket(url);
const started = Date.now();
const stamp = () => `${((Date.now() - started) / 1000).toFixed(1)}s`;

ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'auth', token, accessKey: process.env.TERMDESK_ACCESS_KEY || undefined }));
});

let created = false;
ws.on('message', (raw) => {
  const frame = JSON.parse(raw.toString());
  switch (frame.type) {
    case 'auth.ok':
      console.log(`${stamp()} auth.ok`);
      ws.send(JSON.stringify({ type: 'chat.create', engine: 'opencode', cwd: 'E:/aiPic/termdesk' }));
      return;
    case 'chats':
      return;
    case 'chat': {
      console.log(`${stamp()} chat  id=${frame.id} engine=${frame.engine} model=${frame.model} events=${(frame.events ?? []).length}`);
      if (!created) {
        created = true;
        const text = 'run ls and tell me one file name';
        console.log(`${stamp()} -> chat.send "${text}"`);
        ws.send(JSON.stringify({ type: 'chat.send', chatId: frame.id, text }));
      }
      return;
    }
    case 'chat.event': {
      const item = frame.item ?? {};
      console.log(`${stamp()} chat.event  kind=${item.kind} role=${item.role} stream=${item.streaming ?? false} :: ${String(item.text ?? '').slice(0, 80)}`);
      return;
    }
    case 'chat.status':
      console.log(`${stamp()} chat.status ${frame.status}`);
      return;
    case 'chat.turn':
      console.log(`${stamp()} chat.turn  ${frame.state}`);
      return;
    case 'chat.sent':
      console.log(`${stamp()} chat.sent`);
      return;
    case 'chat.approval':
      console.log(`${stamp()} chat.approval ${frame.title ?? ''} ${frame.detail ?? ''} (${(frame.options ?? []).map((o) => o.id).join('/')})`);
      // Approve once, so the turn can finish.
      if (!frame.state) {
        ws.send(JSON.stringify({ type: 'chat.approve', requestId: frame.requestId, optionId: 'allow_once' }));
        console.log(`${stamp()} -> approved once`);
      }
      return;
    default:
      console.log(`${stamp()} ${frame.type}  ${JSON.stringify(frame).slice(0, 160)}`);
  }
});

ws.on('error', (err) => console.log('socket error:', err.message));
setTimeout(() => { console.log(`${stamp()} --- done observing ---`); ws.close(); process.exit(0); }, 150000);