import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';

test('optional demo fixture labels synthetic output and resumes only its own records', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-ui-demo-'));
  const script = path.join(import.meta.dirname, 'ui-demo-assistant.mjs');
  const run = (args) => spawnSync(process.execPath, [script, ...args], {
    encoding: 'utf8', windowsHide: true,
    env: { ...process.env, TERMDESK_UI_DEMO_STORE: path.join(dir, 'sessions.json') },
  });
  try {
    const first = run(['--print', 'synthetic input']);
    assert.equal(first.status, 0, first.stderr);
    const frames = first.stdout.trim().split('\n').map(JSON.parse);
    assert.match(frames.filter((frame) => frame.type === 'assistant').map((frame) => frame.text).join(''), /未调用模型/);
    const id = frames[0].session_id;
    const second = run(['--print', '--resume', id, 'another input']);
    assert.equal(second.status, 0);
    assert.match(second.stdout, /第 2 次/);
    const list = run(['--list-sessions']);
    assert.equal(JSON.parse(list.stdout.trim()).session_id, id);
    assert.equal(run(['--print', '--resume', 'missing', 'input']).status, 3);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
