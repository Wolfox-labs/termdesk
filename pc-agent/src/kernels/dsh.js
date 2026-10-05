/**
 * The DSH `sdk` profile, made self-contained.
 *
 * Where does `dsh --profile sdk` learn which providers exist? From the shared
 * settings file `~/.dsh/settings.yaml` (dsh-settings-file). On this machine that
 * file is gone - only `settings.yaml.bak-before-fluxion`, `settings.yaml.bak-
 * before-yzj-high` and `settings.yaml.imported` remain - and the one place the
 * `wolfox` provider still exists is the DESKTOP profile's own patch, which the
 * sdk profile never reads. The runtime therefore refused TermDesk's route at the
 * handshake, before any turn could run:
 *
 *   initialize -> -32603: no adapter registered for provider "wolfox"
 *
 * ...which the phone renders as "无法启动 DSH 运行时" on a machine where the DSH
 * desktop app talks to that same model perfectly well.
 *
 * The fix uses the mechanism DSH already provides: `--patch <overlay>`, a profile
 * overlay applied after every bundle layer. TermDesk writes its own overlay, so
 * the route it asks for is defined by TermDesk instead of by whichever profile
 * another app happened to configure. Nothing under `~/.dsh` is written.
 *
 * One more rule from DSH's own patch format: a patch REPLACES the whole config of
 * the row it targets, so the single row we own (`llm-pi-ai`, the provider
 * registry) is restated in full rather than merged.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Providers the SDK runtime can mount on its own, needing no overlay. */
const SELF_MOUNTED = new Set(['deepseek-official']);

/**
 * Known third-party providers TermDesk can address.
 *
 * These are facts about the provider, not about one machine's DSH install: the
 * endpoint and the env var holding the key. The key itself is never copied -
 * `apiKeyEnv` makes DSH read it from its own credentials store.
 */
const PROVIDERS = {
  wolfox: {
    displayName: 'Wolfox 中转',
    baseURL: 'https://api.wolfoxlabs.xyz/v1',
    api: 'openai-completions',
    apiKeyEnv: 'WOLFOX_API_KEY',
    models: [
      'spe/deepseek-v4-flash',
      'spe/deepseek-v4-pro',
      'spe/deepseek-v4.1-flash',
      'mimo-v2.6-flash',
      'mimo-v2.6-pro',
    ],
  },
};

/** Model ids are written into a YAML document; keep them unable to break it. */
const MODEL_ID = /^[A-Za-z0-9._\/:-]+$/;

/** The overlay lives in TermDesk's own directory, never inside ~/.dsh. */
export function overlayPath(env = process.env) {
  // A machine with a fixed overlay location (or a test) can pin the path.
  if (env.TERMDESK_DSH_OVERLAY) return env.TERMDESK_DSH_OVERLAY;
  return path.join(os.homedir(), '.termdesk', 'dsh', 'sdk-overlay.yml');
}

/**
 * The route description the runtime is spawned with.
 *
 * Everything is overridable so a different machine (or a different relay) is a
 * configuration change, not a code change - the same rule the kernel table
 * follows.
 */
export function routeConfig(env = process.env) {
  const provider = env.TERMDESK_CHAT_PROVIDER || 'wolfox';
  const model = env.TERMDESK_CHAT_MODEL || 'spe/deepseek-v4.1-flash';
  const known = PROVIDERS[provider] ?? null;
  const models = (env.TERMDESK_DSH_MODELS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  return {
    provider,
    model,
    baseURL: env.TERMDESK_DSH_BASE_URL || known?.baseURL || null,
    api: env.TERMDESK_DSH_API || known?.api || 'openai-completions',
    apiKeyEnv: env.TERMDESK_DSH_API_KEY_ENV || known?.apiKeyEnv || null,
    displayName: known?.displayName || provider,
    models: models.length > 0 ? models : (known?.models ?? [model]),
  };
}

/**
 * Render the overlay. Returns null when no overlay is needed (a provider the SDK
 * mounts by itself) or when the route is missing the two facts an overlay row
 * cannot invent: where the endpoint is, and which env var holds the key.
 */
export function renderOverlay(route) {
  if (SELF_MOUNTED.has(route.provider)) return null;
  if (!route.baseURL || !route.apiKeyEnv) return null;

  const models = [...new Set([route.model, ...route.models])];
  for (const id of models) {
    if (!MODEL_ID.test(id)) throw new Error(`DSH 模型 id 不能写进覆盖配置：${id}`);
  }

  const lines = [
    '# Written by TermDesk. Do not edit: it is regenerated from the route in use.',
    '#',
    '# Why it exists: `dsh --profile sdk` has no provider of its own. The shared',
    '# settings file that used to supply one is absent, and the desktop profile\'s',
    '# patch (which does have it) is not read by the sdk profile, so the runtime',
    '# answered the handshake with:',
    '#   no adapter registered for provider "' + route.provider + '"',
    '#',
    '# A patch replaces the targeted row\'s whole config, so the provider registry',
    "# row ('llm-pi-ai') is restated in full.",
    '- id: llm-pi-ai',
    "  name: '@deepseek-ai/dsh-llm-pi-ai'",
    '  config:',
    '    providers:',
    `      ${route.provider}:`,
    `        displayName: ${route.displayName}`,
    `        apiKeyEnv: ${route.apiKeyEnv}`,
    `        api: ${route.api}`,
    `        baseURL: ${route.baseURL}`,
    '        models:',
  ];
  for (const id of models) {
    lines.push(`          - id: ${id}`);
    lines.push('            contextWindow: 1000000');
    lines.push('            maxTokens: 128000');
    lines.push('            input:');
    lines.push('              - text');
    lines.push('              - image');
  }
  lines.push('    defaultContextWindow: 262144');
  lines.push('    defaultMaxTokens: 32768');
  lines.push('    defaultInput:');
  lines.push('      - text');
  lines.push('    headers: {}');
  lines.push('');
  return lines.join('\n');
}

/**
 * Write the overlay for the current route and return its path, or null when the
 * route needs none. Byte-identical content is not rewritten, so a restarted
 * runtime cannot make the file look changed when nothing did.
 */
export function ensureOverlay(route = routeConfig()) {
  const text = renderOverlay(route);
  if (text === null) return null;
  const file = overlayPath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== text) {
    fs.writeFileSync(file, text, 'utf8');
  }
  return file;
}

/** The full argv for the runtime: profile first, then the overlay that makes it resolvable. */
export function runtimeArgs(bin, { profile = 'sdk', patch = null } = {}) {
  const args = [bin, '--profile', profile];
  if (patch) args.push('--patch', patch);
  return args;
}
