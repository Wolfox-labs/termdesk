/**
 * Codex provider-configuration checks.
 *
 * Runs against a SANDBOX copy of a realistic config.toml. The real ~/.codex is
 * never touched: editing live agent configuration must not be a side effect of
 * running tests. A copy of the user's actual config is used as the fixture so
 * the parser is exercised against real complexity (comments, projects, skills,
 * nested tables) rather than a toy file.
 *
 *   node tools/codex-config-test.js
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Point the module at a scratch directory BEFORE importing it.
const SANDBOX = path.join(os.tmpdir(), `termdesk-codex-${Date.now()}`);
fs.mkdirSync(SANDBOX, { recursive: true });
process.env.TERMDESK_CODEX_DIR = SANDBOX;

const { applyProviderConfig, readCodexConfig, restoreBackup } = await import('../src/codexconfig.js');

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` 鈥?${detail}` : ''}`);
};

// --- Build a fixture modelled on the real file ---
const REAL = path.join(os.homedir(), '.codex', 'config.toml');
const FIXTURE = fs.existsSync(REAL)
  ? fs.readFileSync(REAL, 'utf8')
  : `model = "gpt-5"
model_provider = "openai"

[desktop]
conversationDetailMode = "STEPS_PROSE"
appearanceTheme = "dark"

[projects.'c:\\users\\alice\\project']
trust_level = "trusted"
`;

const CONFIG = path.join(SANDBOX, 'config.toml');
fs.writeFileSync(CONFIG, FIXTURE, 'utf8');

// Preserve markers we expect to survive untouched.
const preservedChecks = [
  ['[desktop] 娈靛瓨鍦?, /\[desktop\]/],
  ['conversationDetailMode 淇濈暀', /conversationDetailMode/],
  ['appearanceTheme 淇濈暀', /appearanceTheme/],
  ['projects 淇′换绾у埆淇濈暀', /trust_level/],
];
if (/\[\[skills\.config\]\]/.test(FIXTURE)) preservedChecks.push(['skills.config 淇濈暀', /\[\[skills\.config\]\]/]);
if (/preferred_auth_method/.test(FIXTURE)) preservedChecks.push(['preferred_auth_method 淇濈暀', /preferred_auth_method/]);

try {
  // --- read before any change ---
  const before = await readCodexConfig();
  check('reads the config without modifying it', before.exists === true, before.configPath);
  check('reports the existing model', typeof before.model === 'string', before.model);
  check('never exposes an API key', before.providers.every((p) => !('token' in p) && !('apiKey' in p)));

  const originalBytes = fs.readFileSync(CONFIG, 'utf8');

  // --- reject bad input before writing ---
  const badProvider = await applyProviderConfig({ providerId: 'nonexistent', model: 'x' });
  check('rejects an unknown provider', badProvider.ok === false && badProvider.code === 'bad_provider', badProvider.message);

  const badKey = await applyProviderConfig({
    providerId: 'deepseek', model: 'deepseek-flash', apiKey: 'wrong-prefix-key',
  });
  check('rejects a malformed API key', badKey.ok === false && badKey.code === 'bad_key', badKey.message);

  const afterRejects = fs.readFileSync(CONFIG, 'utf8');
  check('config untouched after rejected writes', afterRejects === originalBytes);

  // --- apply without a key (must not invent one) ---
  const noKey = await applyProviderConfig({ providerId: 'deepseek', model: 'deepseek-flash' });
  check('applies a provider', noKey.ok === true, noKey.message);
  const afterNoKey = fs.readFileSync(CONFIG, 'utf8');
  check('does not invent an API key', !/experimental_bearer_token\s*=\s*""/.test(afterNoKey));

  // --- every pre-existing section survived ---
  for (const [name, re] of preservedChecks) {
    check(`preserved: ${name}`, re.test(afterNoKey));
  }

  // --- the fields we own were actually written ---
  check('sets model', /^model\s*=\s*"deepseek-flash"/m.test(afterNoKey));
  check('sets model_provider', /^model_provider\s*=\s*"deepseek"/m.test(afterNoKey));
  check('sets reasoning effort', /^model_reasoning_effort\s*=\s*"high"/m.test(afterNoKey));
  check('adds the provider table', /\[model_providers\.deepseek\]/.test(afterNoKey));
  check('provider uses the responses wire API', /wire_api\s*=\s*"responses"/.test(afterNoKey));
  check('adds enabled-reasoning-efforts', /enabled-reasoning-efforts\s*=\s*\["low", "high", "max"\]/.test(afterNoKey));

  // --- the model catalog ---
  const catalog = JSON.parse(fs.readFileSync(path.join(SANDBOX, 'models.json'), 'utf8'));
  check('writes a model catalog', Array.isArray(catalog.models), `${catalog.models.length} models`);
  const flash = catalog.models.find((m) => m.slug === 'deepseek-flash');
  check('catalog declares the context window', flash?.context_window === 1048576, String(flash?.context_window));
  check('catalog declares reasoning levels', Array.isArray(flash?.supported_reasoning_levels)
    && flash.supported_reasoning_levels.length === 3);
  check('catalog marks vision support', (flash?.input_modalities ?? []).includes('image'));
  check('catalog declares freeform apply_patch', flash?.apply_patch_tool_type === 'freeform');

  // --- a second apply replaces rather than duplicates ---
  const second = await applyProviderConfig({
    providerId: 'deepseek', model: 'deepseek-v4-pro', apiKey: 'sk-test-key-123', reasoningEffort: 'max',
  });
  check('re-applies with a different model', second.ok === true, second.message);
  const afterSecond = fs.readFileSync(CONFIG, 'utf8');
  check('provider table not duplicated', (afterSecond.match(/\[model_providers\.deepseek\]/g) ?? []).length === 1);
  check('model switched', /^model\s*=\s*"deepseek-v4-pro"/m.test(afterSecond));
  check('reasoning effort switched', /^model_reasoning_effort\s*=\s*"max"/m.test(afterSecond));
  check('writes the API key when supplied', /experimental_bearer_token\s*=\s*"sk-test-key-123"/.test(afterSecond));

  const catalog2 = JSON.parse(fs.readFileSync(path.join(SANDBOX, 'models.json'), 'utf8'));
  const slugs = catalog2.models.map((m) => m.slug);
  check('catalog has no duplicate slugs', new Set(slugs).size === slugs.length, slugs.join(','));
  check('catalog keeps both models', slugs.includes('deepseek-flash') && slugs.includes('deepseek-v4-pro'));

  // --- a third apply must not wipe the stored key ---
  const third = await applyProviderConfig({ providerId: 'deepseek', model: 'deepseek-flash' });
  check('re-apply without a key succeeds', third.ok === true, third.message);
  const afterThird = fs.readFileSync(CONFIG, 'utf8');
  check('existing API key preserved when omitted', /sk-test-key-123/.test(afterThird));

  // --- context window override ---
  const ctx = await applyProviderConfig({
    providerId: 'deepseek', model: 'deepseek-flash', contextWindow: 65536,
  });
  const catalog3 = JSON.parse(fs.readFileSync(path.join(SANDBOX, 'models.json'), 'utf8'));
  check('honours a context-window override', ctx.ok && catalog3.models.every((m) => m.context_window === 65536),
    String(catalog3.models[0]?.context_window));

  // --- backups ---
  const backups = await fsp.readdir(path.join(SANDBOX, 'termdesk-backups'));
  check('created backups before writing', backups.filter((b) => b.startsWith('config-')).length >= 2,
    `${backups.length} files`);

  // --- restore ---
  const listBefore = await readCodexConfig();
  const restore = await restoreBackup(null);
  check('restores the newest backup', restore.ok === true, restore.message);
  check('backups are listed for the client', listBefore.backups.length > 0, `${listBefore.backups.length} backups`);

  const restoreTraversal = await restoreBackup('..\\..\\config.toml');
  check('refuses a traversal in the backup name', restoreTraversal.ok === false && restoreTraversal.code === 'bad_name',
    restoreTraversal.message);
  const restoreMissing = await restoreBackup('config-does-not-exist.toml');
  check('reports a missing backup', restoreMissing.ok === false && restoreMissing.code === 'not_found',
    restoreMissing.message);

  // --- missing config file ---
  const empty = path.join(os.tmpdir(), `termdesk-empty-${Date.now()}`);
  fs.mkdirSync(empty, { recursive: true });
  process.env.TERMDESK_CODEX_DIR = empty;
  const missing = await applyProviderConfig({ providerId: 'deepseek', model: 'deepseek-flash' });
  check('refuses to run without a config.toml', missing.ok === false && missing.code === 'no_config', missing.message);
  const emptyRead = await readCodexConfig();
  check('read reports a missing config instead of throwing', emptyRead.exists === false);
  process.env.TERMDESK_CODEX_DIR = SANDBOX;
} catch (err) {
  check('codex config harness completed', false, err.message);
}

// --- confirm the real config was never touched ---
if (fs.existsSync(REAL)) {
  const realNow = fs.readFileSync(REAL, 'utf8');
  check('the real ~/.codex/config.toml is unchanged', realNow === FIXTURE);
} else {
  console.log('SKIP  no real config present to compare');
}

fs.rmSync(SANDBOX, { recursive: true, force: true });

const failures = results.filter((r) => !r.passed).length;
console.log(`\nCodex config: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);
