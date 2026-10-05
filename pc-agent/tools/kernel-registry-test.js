/**
 * Kernel registry: the single table the picker, the probe and the chat pipeline
 * all read. These checks pin down the honesty rules, because those are what stop
 * the phone from offering something that cannot actually run.
 *
 *   node tools/kernel-registry-test.js
 */
import { listKernels, chatEngineIds, spawnSpec, kernelTier, isAcpKernel, shimSpec } from '../src/kernels/registry.js';
import { CHAT_ENGINES, ChatManager } from '../src/chat.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const list = listKernels();
const byId = Object.fromEntries(list.map((k) => [k.id, k]));
console.log(`kernels: ${list.map((k) => `${k.id}(${k.tier})`).join(' ')}\n`);

check('the table is not empty', list.length > 0, `${list.length}`);
check('codex and dsh are native', byId.codex?.tier === 'native' && byId.dsh?.tier === 'native');
check('every tier is a known value',
  list.every((k) => ['native', 'acp', 'shim', 'unsupported'].includes(k.tier)),
  [...new Set(list.map((k) => k.tier))].join(','));
check('only native and acp kernels are selectable',
  list.every((k) => !k.selectable || k.tier === 'native' || k.tier === 'acp'));
check('an unavailable kernel is never selectable',
  list.every((k) => !k.available || true) && list.every((k) => k.available || k.selectable === false));
check('a shim kernel is installed but not selectable',
  !byId.qoder || (byId.qoder.available === true && byId.qoder.selectable === false));
check('an unsupported kernel says why',
  !byId.antigravity || byId.antigravity.detail.length > 0);

// The chat pipeline and the picker must agree, or the phone offers a kernel the
// pipeline rejects.
check('chat engines equal the selectable kernels',
  chatEngineIds().join(',') === list.filter((k) => k.selectable).map((k) => k.id).join(','),
  chatEngineIds().join(','));
check('CHAT_ENGINES is the same list', CHAT_ENGINES.join(',') === chatEngineIds().join(','), CHAT_ENGINES.join(','));
check('ACP engines are recognised', isAcpKernel('opencode') === true || !byId.opencode);
check('an unknown id has no tier', kernelTier('definitely-not-a-kernel') === null);

// Spawn specs must be complete: an ACP kernel is spawned with its protocol
// subcommand, and never as a bare path with no args.
for (const kernel of list.filter((k) => k.tier === 'acp' && k.available)) {
  const spec = spawnSpec(kernel.id);
  check(`${kernel.id}: spawn spec ends with the acp subcommand`,
    Array.isArray(spec?.args) && spec.args[spec.args.length - 1] === 'acp',
    JSON.stringify(spec));
  check(`${kernel.id}: picker shows the kernel file, not the runtime host`,
    typeof kernel.path === 'string' && kernel.path.length > 0 && !/\bnode\.exe$/i.test(kernel.path),
    kernel.path);
}
check('a shim keeps its recorded CLI contract',
  (shimSpec('command-code')?.resumeArgs?.length ?? 0) > 0 || !byId['command-code']);
check('spawn spec for an unknown id is null', spawnSpec('definitely-not-a-kernel') === null);

// The pipeline itself: every selectable engine is accepted, and anything else
// is refused with an explanation that names the tier.
const manager = new ChatManager();
for (const id of chatEngineIds()) {
  const created = manager.create({ engine: id, cwd: process.cwd() });
  check(`chat.create accepts "${id}"`, created.ok === true, created.message ?? '');
  if (created.ok) manager.close(created.chat.id);
}
const refused = manager.create({ engine: 'qoder' });
check('chat.create refuses a shim engine', refused.ok === false && refused.code === 'bad_engine', refused.message);
check('the refusal explains the tier', String(refused.message).includes('shim'), refused.message);
const unknown = manager.create({ engine: 'nope' });
check('chat.create refuses an unknown engine', unknown.ok === false && unknown.code === 'bad_engine');
manager.disposeAll();

const failures = results.filter((r) => !r.passed).length;
console.log(`\nRegistry: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);