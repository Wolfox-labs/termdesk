/** Free metadata probe: what models does one ACP kernel offer for a new session? */
import { AcpKernel } from '../src/kernels/acp.js';
import { spawnSpec } from '../src/kernels/registry.js';

const engine = process.argv[2] ?? 'opencode';
const filter = process.argv[3] ?? '';
const spec = spawnSpec(engine);
if (!spec) { console.error('no such kernel:', engine); process.exit(1); }
const kernel = new AcpKernel({ id: engine, bin: spec.bin, args: spec.args, log: () => {} });
await kernel.ensureStarted();
const sid = await kernel.newSession({ cwd: process.cwd() });
const { current, models } = kernel.availableModels(sid);
console.log(`kernel=${engine} session=${sid} current=${current} models=${models.length}`);
const re = filter ? new RegExp(filter, 'i') : null;
for (const m of models.filter((m) => !re || re.test(JSON.stringify(m))).slice(0, 30)) {
  console.log(`  ${m.modelId ?? m.id ?? '?'}  ${m.name ?? ''}`);
}
kernel.dispose();
process.exit(0);
