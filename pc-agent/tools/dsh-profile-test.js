/**
 * The DSH sdk profile's overlay, without spawning anything.
 *
 * The bug this pins down: `dsh --profile sdk` has no provider registry of its own
 * on this machine (the shared settings file is absent, and the desktop profile's
 * patch is not read here), so TermDesk's handshake was refused with
 * `no adapter registered for provider "wolfox"` and the phone never saw a word.
 *
 * These checks are deliberately offline: they assert the overlay text and the
 * spawn contract, so a change that would silently reintroduce that failure fails
 * here instead of on the phone, and no model is ever called.
 *
 *   node tools/dsh-profile-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ensureOverlay, overlayPath, renderOverlay, routeConfig, runtimeArgs } from '../src/kernels/dsh.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- the route -----------------------------------------------------------------
const route = routeConfig({});
check('the default route is the one the chat pipeline asks for',
  route.provider === 'wolfox' && route.model === 'spe/deepseek-v4.1-flash',
  `${route.provider}/${route.model}`);
check('the route knows where the endpoint is', route.baseURL === 'https://api.wolfoxlabs.xyz/v1', route.baseURL);
check('the route names the env var holding the key, and never the key',
  route.apiKeyEnv === 'WOLFOX_API_KEY' && !JSON.stringify(route).toLowerCase().includes('sk-'));
const envRoute = routeConfig({ TERMDESK_CHAT_PROVIDER: 'wolfox', TERMDESK_CHAT_MODEL: 'mimo-v2.6-flash' });
check('the route is configurable, not hard-coded', envRoute.model === 'mimo-v2.6-flash');

// --- the overlay ---------------------------------------------------------------
const text = renderOverlay(route);
check('an overlay is produced for a third-party provider', typeof text === 'string' && text.length > 0);
check('it patches the row that owns the provider registry', text.includes('- id: llm-pi-ai'));
check('it declares the provider the handshake asks for', text.includes('      wolfox:'));
check('it points DSH at the key it already stores', text.includes('apiKeyEnv: WOLFOX_API_KEY'));
check('it carries the endpoint', text.includes('baseURL: https://api.wolfoxlabs.xyz/v1'));
check('every model TermDesk may ask for is declared', ['spe/deepseek-v4-flash', 'spe/deepseek-v4.1-flash', 'mimo-v2.6-flash']
  .every((id) => text.includes(`- id: ${id}`)));
check('a per-chat model that is not in the table still gets declared',
  renderOverlay({ ...route, model: 'some/future-model' }).includes('- id: some/future-model'));
check('a provider the SDK mounts itself needs no overlay',
  renderOverlay({ ...route, provider: 'deepseek-official' }) === null);
check('an unknown provider with no endpoint is not invented',
  renderOverlay({ ...route, provider: 'mystery', baseURL: null, apiKeyEnv: null }) === null);
let rejected = false;
try { renderOverlay({ ...route, model: 'bad\nid: x' }); } catch { rejected = true; }
check('a model id that could break the YAML is refused, not written', rejected);

// --- writing it ----------------------------------------------------------------
const scratch = path.join(os.tmpdir(), `termdesk-dsh-overlay-${process.pid}.yml`);
process.env.TERMDESK_DSH_OVERLAY = scratch;
const written = ensureOverlay(route);
check('ensureOverlay writes it where it says it does', written === scratch && fs.existsSync(scratch), written);
check('the file on disk is the rendered overlay', fs.readFileSync(scratch, 'utf8') === text);
const firstStamp = fs.statSync(scratch).mtimeMs;
ensureOverlay(route);
check('writing it twice with the same route does not touch the file',
  fs.statSync(scratch).mtimeMs === firstStamp);
check('the overlay lives outside ~/.dsh, so another app\'s config is never rewritten',
  !path.resolve(overlayPath({})).includes(`${path.sep}.dsh${path.sep}`), overlayPath({}));
fs.unlinkSync(scratch);
delete process.env.TERMDESK_DSH_OVERLAY;

// --- the spawn contract --------------------------------------------------------
const withPatch = runtimeArgs('C:\\dsh\\bin.js', { patch: 'C:\\overlay.yml' });
check('the runtime is spawned with the profile and the overlay',
  withPatch.join(' ') === 'C:\\dsh\\bin.js --profile sdk --patch C:\\overlay.yml', withPatch.join(' '));
check('no overlay means no --patch flag', !runtimeArgs('x', {}).includes('--patch'));

const failures = results.filter((r) => !r.passed).length;
console.log(`\nDSH profile: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
