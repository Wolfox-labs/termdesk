/**
 * 功能包走 WebSocket 的端到端检查。
 *
 * 为什么要它：手机可能是经中继连上电脑的，那种情况下 HTTP 地址指向中继、不是电脑，
 * 「功能包」页就永远停在"正在读取"。这条用例直接照搬手机的握手：
 * 认证 → 要清单 → 要一个包 → 拼起来 → 核对 sha256。
 *
 *   node tools/feature-ws-test.js            # 默认 ws://127.0.0.1:7420
 *   TERMDESK_WS=ws://host:port node tools/feature-ws-test.js
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { WebSocket } from 'ws';

import { PROTOCOL_VERSION } from '../src/protocol.js';

const url = process.env.TERMDESK_WS || 'ws://127.0.0.1:7420';
const tokenFile = process.env.TERMDESK_TOKEN_FILE || path.join(os.homedir(), '.termdesk', 'token');
const token = process.env.TERMDESK_TOKEN || fs.readFileSync(tokenFile, 'utf8').trim();

const socket = new WebSocket(url);
let stage = 'auth';
const chunks = [];
let packId = null;
let expected = null;

const timer = setTimeout(() => {
  console.error(`超时在「${stage}」这一步（${url}）`);
  process.exit(1);
}, 20_000);

function done(code, message) {
  clearTimeout(timer);
  if (message) console.log(message);
  try { socket.close(); } catch { /* 已经关了 */ }
  process.exit(code);
}

socket.on('open', () => {
  socket.send(JSON.stringify({ type: 'auth', token, protocol: PROTOCOL_VERSION }));
});

socket.on('message', (raw) => {
  let frame;
  try { frame = JSON.parse(String(raw)); } catch { return; }

  if (frame.type === 'auth.fail') {
    done(1, `认证失败：${frame.reason ?? '未知原因'}`);
    return;
  }

  if (stage === 'auth' && frame.type === 'auth.ok') {
    assert.ok(Array.isArray(frame.capabilities), 'auth.ok 要带 capabilities');
    assert.ok(
      frame.capabilities.includes('feature.ws'),
      `这台 agent 没声明 feature.ws（capabilities=${frame.capabilities.join(',')}）`,
    );
    stage = 'list';
    socket.send(JSON.stringify({ type: 'features' }));
    return;
  }

  if (stage === 'list' && frame.type === 'features') {
    const packs = Array.isArray(frame.packs) ? frame.packs : [];
    assert.ok(packs.length > 0, `电脑上没有任何功能包${frame.error ? `（${frame.error}）` : ''}`);
    const usable = packs.find((p) => p.present && p.sha256);
    assert.ok(usable, '没有一个是"能下载"的包');
    packId = usable.id;
    expected = usable.sha256.toLowerCase();
    stage = 'pack';
    socket.send(JSON.stringify({ type: 'feature.get', id: packId, requestId: 'test-1' }));
    return;
  }

  if (stage === 'pack' && frame.type === 'feature.chunk') {
    assert.equal(frame.id, packId);
    chunks.push(Buffer.from(frame.data, 'base64'));
    if (!frame.eof) return;
    const bytes = Buffer.concat(chunks);
    const actual = createHash('sha256').update(bytes).digest('hex');
    assert.equal(actual, expected, '拼起来的包与清单里的 sha256 不一致');
    assert.equal(bytes.length, frame.total, '字节数与分块里报的 total 不一致');
    done(0, `PASS  清单与包体都走 WebSocket：${packId} ${bytes.length} 字节，sha256 对得上`);
  }
});

socket.on('error', (err) => done(1, `连不上 ${url}：${err.message}`));
