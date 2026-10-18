/**
 * The DSH SDK route and TermDesk-owned overlay, without spawning DSH.
 *
 * These checks are offline: they verify that missing configuration is reported,
 * explicit settings are carried through, and the overlay does not invent model
 * capabilities. No model is called.
 *
 *   node tools/dsh-profile-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ensureOverlay, overlayPath, renderOverlay, routeConfig, routeConfigError, runtimeArgs } from '../src/kernels/dsh.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const empty = routeConfig({});
check('an empty environment does not select a provider or model',
  empty.provider === null && empty.model === null);
check('an empty environment returns a clear missing-route explanation',
  routeConfigError(empty)?.includes('TERMDESK_CHAT_PROVIDER')
    && routeConfigError(empty)?.includes('TERMDESK_CHAT_MODEL'));

const env = {
  TERMDESK_CHAT_PROVIDER: 'example-gateway',
  TERMDESK_CHAT_MODEL: 'example/model',
  TERMDESK_DSH_BASE_URL: 'https://gateway.example.invalid/v1',
  TERMDESK_DSH_API: 'openai-completions',
  TERMDESK_DSH_API_KEY_ENV: 'EXAMPLE_API_KEY',
  TERMDESK_DSH_MODELS: 'example/model, example/other-model',
};
const route = routeConfig(env);
check('the route reads explicit provider, model, endpoint, API type and key-variable name',
  route.provider === env.TERMDESK_CHAT_PROVIDER
    && route.model === env.TERMDESK_CHAT_MODEL
    && route.baseURL === env.TERMDESK_DSH_BASE_URL
    && route.api === env.TERMDESK_DSH_API
    && route.apiKeyEnv === env.TERMDESK_DSH_API_KEY_ENV);
check('a complete explicit third-party route is accepted', routeConfigError(route) === null);
check('no secret value is included in the route', !JSON.stringify(route).includes('secret'));
check('a per-chat provider cannot be paired with another provider\'s endpoint',
  routeConfigError(routeConfig(env, { provider: 'other-gateway', model: 'other/model' }))
    ?.includes('must match the selected provider'));

const text = renderOverlay(route);
check('an overlay is produced for the explicitly configured third-party provider',
  typeof text === 'string' && text.length > 0);
check('it patches the row that owns the provider registry', text.includes('- id: llm-pi-ai'));
check('it declares the configured provider and endpoint',
  text.includes('      "example-gateway":')
    && text.includes(`baseURL: "${env.TERMDESK_DSH_BASE_URL}"`));
check('it declares configured models without claiming unsupported capabilities',
  text.includes('- id: "example/model"') && text.includes('- id: "example/other-model"')
    && !/contextWindow|maxTokens|defaultInput|input:\n\s+- image/.test(text));
check('an explicitly selected per-chat model is also declared',
  renderOverlay({ ...route, model: 'example/future-model' }).includes('- id: "example/future-model"'));
check('a provider the SDK mounts itself needs no endpoint overlay',
  renderOverlay({ ...route, provider: 'deepseek-official' }) === null);
check('an unknown provider without endpoint, API type, and key-variable name remains unconfigured',
  routeConfigError(routeConfig({ TERMDESK_CHAT_PROVIDER: 'custom', TERMDESK_CHAT_MODEL: 'custom/model' }))
    ?.includes('TERMDESK_DSH_BASE_URL'));
let missingOverlayRejected = false;
try { ensureOverlay(empty); } catch (error) {
  missingOverlayRejected = /DSH 尚未配置可用路由/.test(error.message);
}
check('ensureOverlay rejects a missing route instead of silently skipping its patch', missingOverlayRejected);
let rejected = false;
try { renderOverlay({ ...route, model: 'bad\nid: x' }); } catch { rejected = true; }
check('a model id that could break the YAML is refused, not written', rejected);
const oddRoute = routeConfig({
  ...env,
  TERMDESK_CHAT_PROVIDER: 'custom:gateway',
  TERMDESK_DSH_DISPLAY_NAME: 'Custom: # Gateway',
  TERMDESK_DSH_BASE_URL: 'https://gateway.example.invalid/v1?mode=a#b',
});
const oddYaml = renderOverlay(oddRoute);
check('provider identifiers and descriptive strings are emitted as quoted YAML scalars',
  oddYaml.includes('      "custom:gateway":')
    && oddYaml.includes('displayName: "Custom: # Gateway"')
    && oddYaml.includes('baseURL: "https://gateway.example.invalid/v1?mode=a#b"'));
let invalidKeyRejected = false;
try { renderOverlay({ ...route, apiKeyEnv: 'BAD\nvalue: x' }); } catch { invalidKeyRejected = true; }
check('an invalid credential variable name is refused', invalidKeyRejected);

const scratch = path.join(os.tmpdir(), `termdesk-dsh-overlay-${process.pid}.yml`);
process.env.TERMDESK_DSH_OVERLAY = scratch;
const written = ensureOverlay(route);
check('ensureOverlay writes only to its configured scratch location',
  written === scratch && fs.existsSync(scratch), written);
check('the file on disk matches the rendered overlay', fs.readFileSync(scratch, 'utf8') === text);
const firstStamp = fs.statSync(scratch).mtimeMs;
ensureOverlay(route);
check('identical overlay content is not rewritten', fs.statSync(scratch).mtimeMs === firstStamp);
check('the default overlay lives outside ~/.dsh',
  !path.resolve(overlayPath({})).includes(`${path.sep}.dsh${path.sep}`), overlayPath({}));
fs.unlinkSync(scratch);
delete process.env.TERMDESK_DSH_OVERLAY;

const withPatch = runtimeArgs('C:\\dsh\\bin.js', { patch: 'C:\\overlay.yml' });
check('the runtime receives the profile and overlay path',
  withPatch.join(' ') === 'C:\\dsh\\bin.js --profile sdk --patch C:\\overlay.yml', withPatch.join(' '));
check('a self-mounted provider has no --patch flag', !runtimeArgs('x', {}).includes('--patch'));

// Load chat.js only after registering a fake DSH executable. ChatManager.create
// must reject a missing route and accept an explicit SDK-native route without
// starting any process or sending a prompt.
const saved = Object.fromEntries([
  'TERMDESK_DSH', 'TERMDESK_CHAT_PROVIDER', 'TERMDESK_CHAT_MODEL',
].map((key) => [key, process.env[key]]));
process.env.TERMDESK_DSH = process.execPath;
delete process.env.TERMDESK_CHAT_PROVIDER;
delete process.env.TERMDESK_CHAT_MODEL;
try {
  const { ChatManager } = await import('../src/chat.js');
  const manager = new ChatManager();
  const missing = manager.create({ title: 'missing route', engine: 'dsh' });
  check('chat.create returns a clear unconfigured error before creating a DSH chat',
    missing.ok === false && missing.code === 'unconfigured'
      && /TERMDESK_CHAT_PROVIDER/.test(missing.message), missing.message);
  process.env.TERMDESK_CHAT_PROVIDER = 'deepseek-official';
  process.env.TERMDESK_CHAT_MODEL = 'fixture-model';
  const configured = manager.create({ title: 'configured route', engine: 'dsh' });
  check('chat.create accepts an explicitly configured SDK-native provider and model',
    configured.ok === true && configured.chat?.provider === 'deepseek-official'
      && configured.chat?.model === 'fixture-model');
  manager.disposeAll();
} finally {
  for (const [key, value] of Object.entries(saved)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
}

const failures = results.filter((r) => !r.passed).length;
console.log(`\nDSH route: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
