/**
 * Live ACP handshake against the kernels actually installed on this machine.
 *
 * Metadata only: `initialize` + the session API. No prompt is ever sent, so no
 * model is called and nothing is billed. What is asserted is the contract the
 * chat pipeline depends on:
 *
 *   initialize     the kernel declares protocol version 1 and its capabilities
 *   session/list   the kernel can hand back its own session index
 *   session/new    a session id can be obtained for a working directory
 *   session/load   a session can be replayed (this is "open history")
 *
 * A kernel that is not installed is reported as SKIP, not as a failure: the
 * suite has to stay meaningful on a machine without OpenCode.
 *
 *   node tools/acp-live-test.js
 */
import fs from 'node:fs';
import { AcpKernel } from '../src/kernels/acp.js';
import { acpKernels, spawnSpec } from '../src/kernels/registry.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};
const skip = (name, detail) => {
  results.push({ name, passed: true, skipped: true });
  console.log(`SKIP  ${name}${detail ? ` — ${detail}` : ''}`);
};

const cwd = process.cwd();
const found = acpKernels();
console.log(`ACP kernels installed: ${found.map((k) => k.id).join(', ') || '(none)'}\n`);
if (found.length === 0) skip('no ACP kernel is installed', 'nothing to check');

for (const entry of found) {
  const spec = spawnSpec(entry.id);
  const kernel = new AcpKernel({ id: entry.id, label: entry.label, bin: spec.bin, args: spec.args, log: () => {} });
  try {
    await kernel.ensureStarted();
    check(`${entry.id}: initialize declared protocol 1`, kernel.capabilities !== null);
    const caps = kernel.sessionSupport();
    check(`${entry.id}: declares session/list`, caps.list === true, JSON.stringify(caps));

    const listed = await kernel.listSessions();
    check(`${entry.id}: session/list returns an array`, Array.isArray(listed.sessions), `${listed.sessions.length} sessions`);
    if (listed.sessions.length > 0) {
      const first = listed.sessions[0];
      check(`${entry.id}: listed session carries an id and cwd`,
        typeof (first.sessionId ?? first.id) === 'string' && typeof first.cwd === 'string',
        `${first.sessionId ?? first.id} @ ${first.cwd}`);
    }

    const sessionId = await kernel.newSession({ cwd });
    check(`${entry.id}: session/new returns an id`, typeof sessionId === 'string' && sessionId.length > 0, sessionId);

    // What the phone's model picker is built from.
    const models = kernel.availableModels(sessionId);
    check(`${entry.id}: the session lists its models`, models.models.length > 0, `${models.models.length} models, current ${models.current}`);
    if (models.models.length > 0) {
      const target = models.models.find((m) => m.id !== models.current) ?? models.models[0];
      let switched = false;
      try {
        await kernel.setModel(sessionId, target.id);
        switched = kernel.availableModels(sessionId).current === target.id;
      } catch (err) {
        switched = false;
        console.log(`      switch error: ${String(err?.message ?? err).slice(0, 120)}`);
      }
      check(`${entry.id}: a model can be switched`, switched, `-> ${target.id}`);
    }

    const replayed = [];
    const listener = (id, update) => { if (id === sessionId) replayed.push(update); };
    kernel.on('update', listener);
    try {
      await kernel.loadSession(sessionId, { cwd });
      check(`${entry.id}: session/load resolves for a fresh session`, true, `replayed ${replayed.length}`);
    } finally {
      kernel.off('update', listener);
    }

    // The replayed updates must be mappable, since that is what the phone gets.
    const mapped = replayed.length === 0 || replayed.every((u) => typeof u?.sessionUpdate === 'string');
    check(`${entry.id}: replayed updates carry a sessionUpdate discriminator`, mapped);
  } catch (err) {
    check(`${entry.id}: ACP session API usable`, false, String(err?.message ?? err).slice(0, 160));
  } finally {
    kernel.dispose();
  }
  console.log('');
}

// The adapter must refuse to pretend: a kernel whose binary is missing cannot
// be started, and that failure has to be a rejection, not a hang.
try {
  const ghost = new AcpKernel({ id: 'ghost', bin: process.execPath, args: ['-e', 'process.exit(0)'], log: () => {} });
  let rejected = false;
  await ghost.ensureStarted().catch(() => { rejected = true; });
  check('a kernel that exits during handshake is reported as a failure', rejected);
  ghost.dispose();
} catch (err) {
  check('a kernel that exits during handshake is reported as a failure', false, String(err?.message ?? err));
}

const failures = results.filter((r) => !r.passed).length;
const skipped = results.filter((r) => r.skipped).length;
console.log(`\nACP live: ${results.length - failures - skipped}/${results.length - skipped} passed${skipped ? `, ${skipped} skipped` : ''}`);
process.exit(failures === 0 ? 0 : 1);