/** Fake frames and fixtures only. No installed assistant or model is invoked. */
import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createFrameHandler } from '../src/handlers.js';
import { ChatContinuity } from '../src/chat/continuity.js';
import { Chat } from '../src/chat/chat.js';
import { kernelCommand, listKernels } from '../src/kernels/registry.js';

function fixture(chats = {}, continuity = new ChatContinuity(), identity = { deviceId: 'observer' }) {
  const frames = [];
  return { frames, handle: createFrameHandler({ chats, continuity, identity,
    send: (type, payload) => frames.push({ type, ...payload }), socket: {}, terminals: {}, shellEnabled: false }) };
}

test('consecutive fs.write frames both acknowledge and persist the latest text', async () => {
  // POSIX defaults permit the home directory, not /tmp. Keep this real write
  // inside the default fence rather than weakening the product guard for tests.
  const dir = fs.mkdtempSync(path.join(os.homedir(), '.termdesk-save-'));
  const file = path.join(dir, 'demo.txt');
  try {
    const { handle, frames } = fixture();
    await handle({ type: 'fs.write', path: file, text: 'first edit' });
    await handle({ type: 'fs.write', path: file, text: 'second edit' });
    const results = frames.filter((frame) => frame.type === 'action.result');
    assert.equal(results.length, 2);
    assert.ok(results.every((result) => result.ok && result.action === 'fs.write' && result.target === file));
    assert.equal(frames.filter((frame) => frame.type === 'fs.written').length, 2);
    assert.equal(fs.readFileSync(file, 'utf8'), 'second edit');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

for (const state of ['idle', 'running', 'expired-running']) {
  for (const type of ['chat.send', 'chat.close', 'chat.approve', 'chat.config', 'chat.cancel', 'chat.terminal.input', 'chat.terminal.stop']) {
    test(`${state}: observer ${type} is refused before mutation`, async () => {
      let now = 1000;
      const leases = new ChatContinuity({ now: () => now, leaseMs: 100 });
      leases.claim('c', { deviceId: 'owner', deviceName: 'Controller' });
      if (state === 'expired-running') now += 101;
      const chats = new Proxy({
        get: () => ({ id: 'c', status: state === 'idle' ? 'idle' : 'running' }),
        approvals: { pending: () => [{ requestId: 'r', chatId: 'c' }] },
      }, {
        get: (target, key) => key in target ? target[key] : () => { assert.fail(`unexpected mutation: ${String(key)}`); },
      });
      const { handle, frames } = fixture(chats, leases);
      await handle({ type, chatId: 'c', text: 'synthetic', terminalId: 't', requestId: 'r', optionId: 'allow' });
      const result = frames.find((frame) => frame.type === 'action.result');
      assert.equal(result?.ok, false);
      assert.equal(result.action, type);
      assert.equal(result.code, state === 'idle' ? 'session_busy' : 'session_running');
    });
  }
}

test('full snapshots repair old revisions and removals even with a matching legacy cursor', () => {
  const chat = new Chat({ id: 'c', engine: 'dsh', cwd: process.cwd() });
  chat.events = [{ seq: 1, text: 'old' }, { seq: 2, text: 'remove me' }];
  chat.seq = 2;
  chat.events[0].text = 'revised';
  chat.events.pop();
  const detail = chat.detail(2);
  assert.equal(detail.eventSync.mode, 'full');
  assert.deepEqual(detail.events, [{ seq: 1, text: 'revised' }]);
  assert.equal('eventCursor' in detail, false);
  assert.equal('continuity' in detail, false);
});

for (const ext of ['cmd', 'bat']) {
  test(`${ext} registration is unavailable with an explicit unsupported-script reason`, () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-script-'));
    const bin = path.join(dir, `assistant.${ext}`);
    const prior = process.env.TERMDESK_ACP_KERNELS;
    fs.writeFileSync(bin, '@echo off\r\necho SYNTHETIC\r\n', { mode: 0o755 });
    process.env.TERMDESK_ACP_KERNELS = JSON.stringify([{ id: 'script-test', bin }]);
    try {
      assert.equal(kernelCommand({ resolve: () => bin }), null);
      const kernel = listKernels().find((item) => item.id === 'script-test');
      assert.equal(kernel.available, false);
      assert.match(kernel.detail, /脚本类型不支持/);
      assert.doesNotMatch(kernel.detail, /未.*找到/);
    } finally {
      if (prior === undefined) delete process.env.TERMDESK_ACP_KERNELS; else process.env.TERMDESK_ACP_KERNELS = prior;
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
}

test('invalid registration files have actionable diagnostics', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-registration-'));
  const prior = process.env.TERMDESK_KERNEL_MANIFEST_DIR;
  const warn = console.warn;
  const warnings = [];
  fs.writeFileSync(path.join(dir, 'broken.json'), '{broken');
  fs.writeFileSync(path.join(dir, 'missing.json'), JSON.stringify({ id: 'missing-bin' }));
  process.env.TERMDESK_KERNEL_MANIFEST_DIR = dir;
  console.warn = (message) => warnings.push(message);
  try {
    listKernels();
    assert.ok(warnings.some((message) => message.includes('broken.json') && message.includes('无法读取')));
    assert.ok(warnings.some((message) => message.includes('missing.json') && message.includes('id / bin')));
  } finally {
    console.warn = warn;
    if (prior === undefined) delete process.env.TERMDESK_KERNEL_MANIFEST_DIR; else process.env.TERMDESK_KERNEL_MANIFEST_DIR = prior;
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('session listing never launches an unavailable Codex executable', async () => {
  const prior = process.env.TERMDESK_CODEX;
  process.env.TERMDESK_CODEX = path.join(os.tmpdir(), 'termdesk-not-installed-codex');
  try {
    const { handle, frames } = fixture({ codexServer: () => assert.fail('unavailable kernel launched') });
    await handle({ type: 'sessions.list', engine: 'codex' });
    const frame = frames.find((item) => item.type === 'sessions');
    assert.deepEqual(frame.sessions, []);
    assert.match(frame.codexSource, /未检测到可运行的 Codex/);
  } finally {
    if (prior === undefined) delete process.env.TERMDESK_CODEX; else process.env.TERMDESK_CODEX = prior;
  }
});
