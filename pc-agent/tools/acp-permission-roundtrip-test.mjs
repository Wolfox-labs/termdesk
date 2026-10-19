/** Does an answered permission actually reach the fake kernel? */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AcpKernel } from '../src/kernels/acp.js';

const testDir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-acp-permission-'));
const fakeAgent = fileURLToPath(new URL('./fake-acp-agent.mjs', import.meta.url));
const kernel = new AcpKernel({
  id: 'permission-roundtrip-test',
  bin: process.execPath,
  args: [fakeAgent],
  log: () => {},
});
const updates = [];
kernel.on('update', (_sessionId, update) => updates.push(update));

const permissionAnswer = new Promise((resolve, reject) => {
  kernel.once('permission', (info) => {
    try {
      assert.ok(info.options.some((option) => option.optionId === 'allow_once'));
      const first = info.respond('allow_once');
      const duplicate = info.respond('allow_once');
      resolve({ optionId: 'allow_once', first, duplicate });
    } catch (error) { reject(error); }
  });
});

try {
  await kernel.ensureStarted();
  const sessionId = await kernel.newSession({ cwd: testDir });
  const [result, permission] = await Promise.all([
    kernel.prompt(sessionId, 'permission round trip fixture'),
    permissionAnswer,
  ]);

  const assistantText = updates
    .filter((update) => update.sessionUpdate === 'agent_message_chunk')
    .map((update) => update.content?.text ?? '')
    .join('');
  const toolUpdates = updates.filter((update) => update.sessionUpdate === 'tool_call_update');

  assert.deepEqual(permission, { optionId: 'allow_once', first: true, duplicate: false });
  assert.equal(result.stopReason, 'end_turn');
  assert.match(assistantText, /permission round trip fixture/);
  assert.ok(updates.some((update) => update.sessionUpdate === 'tool_call'));
  assert.ok(toolUpdates.some((update) => update.status === 'completed'));
  assert.ok(toolUpdates.some((update) => JSON.stringify(update.content).includes('allowed (allow_once)')));
  console.log('PASS ACP permission response reaches fake kernel exactly once and completes its tool call');
} finally {
  kernel.dispose();
  fs.rmSync(testDir, { recursive: true, force: true });
}
