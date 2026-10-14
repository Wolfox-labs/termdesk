/**
 * Normalized kernel manifest checks.
 *
 * The manifest is the boundary between what a kernel happens to expose and
 * what the phone may promise. These checks keep capability claims explicit and
 * prevent a planned editor from being mistaken for a working runtime feature.
 *
 *   node tools/kernel-manifest-test.js
 */
import { listKernels } from '../src/kernels/registry.js';
import { manifestFor, CONFIG_SUPPORT, CONFIG_APPLY } from '../src/kernels/manifest.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const list = listKernels();
const byId = Object.fromEntries(list.map((kernel) => [kernel.id, kernel]));

check('every registry kernel exposes a manifest',
  list.every((kernel) => kernel.manifest && kernel.manifest.id === kernel.id),
  list.filter((kernel) => !kernel.manifest).map((kernel) => kernel.id).join(','));
check('manifest ids stay aligned with registry ids',
  list.every((kernel) => kernel.manifest?.id === kernel.id));
check('manifest support values use the public enum',
  list.every((kernel) => kernel.manifest.config.schema.every((field) => CONFIG_SUPPORT.includes(field.support))));
check('manifest apply scopes use the public enum',
  list.every((kernel) => kernel.manifest.config.schema.every((field) => CONFIG_APPLY.includes(field.apply))));
check('codex can resume with its native transcript',
  byId.codex?.manifest.session.resume === true && byId.codex?.manifest.session.nativeTranscript === true);
check('dsh stays explicit about restart recovery being unavailable',
  byId.dsh?.manifest.session.resume === false && byId.dsh?.manifest.config.requiresRestart === true);
check('ACP model discovery is dynamic',
  byId.opencode?.manifest.model.dynamic === true && byId.opencode?.manifest.model.switch === true);
check('context window is present in the codex schema',
  byId.codex?.manifest.config.schema.some((field) => field.id === 'contextWindow' && field.support === 'supported'));
check('max output is declared planned until the full write path exists',
  list.every((kernel) => kernel.manifest.config.schema.find((field) => field.id === 'maxOutput')?.support === 'planned'));
check('arbitrary phone-side model registration is not claimed yet',
  list.every((kernel) => kernel.manifest.model.add === false));

const unknown = manifestFor('made-up-kernel');
check('unknown kernels receive a safe unsupported manifest',
  unknown.known === false && unknown.driver === 'none' && unknown.session.resume === false,
  JSON.stringify(unknown));

const fields = byId.codex?.manifest.config.schema ?? [];
check('the configuration schema names the requested controls',
  ['provider', 'model', 'effort', 'contextWindow', 'maxOutput', 'inputModalities', 'outputModalities', 'mode']
    .every((id) => fields.some((field) => field.id === id)),
  fields.map((field) => field.id).join(','));

const failures = results.filter((result) => !result.passed).length;
console.log(`\nManifest: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
