/**
 * DSH SDK route selection and its TermDesk-owned provider overlay.
 *
 * The SDK profile's effective provider configuration is not discoverable through
 * the interfaces used by this process. TermDesk therefore requires an explicit
 * provider and model instead of assuming that another DSH profile's settings are
 * inherited. Third-party providers also need an explicit endpoint, API type, and
 * credential-variable name. A separate overlay is written under TermDesk's own
 * directory; no DSH-owned settings file is modified.
 *
 * DSH patches replace the full config of the targeted row. The overlay restates
 * the provider registry row and only supplies values that TermDesk actually
 * knows; it does not invent context-window, output-limit, or modality claims.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * Providers the installed runtime mounts by itself, needing no TermDesk overlay.
 *
 * `deepseek-official` is the SDK's documented fallback adapter. `deepseek-account`
 * and `deepseek` come from the profile's own `deepseek-account-platform` /
 * `llm-deepseek` entries in the shipped base bundle — verified on the installed
 * 0.2.1-rc.1 archive by completing an SDK `initialize` with
 * provider=deepseek-account, model=deepseek-flash (tools/dsh-runtime-test.js).
 * Writing a pi-ai overlay for those would replace the profile's real settings.
 */
const SELF_MOUNTED = new Set(['deepseek-official', 'deepseek-account', 'deepseek']);

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
export function routeConfig(env = process.env, overrides = {}) {
  const provider = overrides.provider || env.TERMDESK_CHAT_PROVIDER || null;
  const model = overrides.model || env.TERMDESK_CHAT_MODEL || null;
  const models = (env.TERMDESK_DSH_MODELS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  return {
    provider,
    configuredProvider: env.TERMDESK_CHAT_PROVIDER || null,
    model,
    baseURL: env.TERMDESK_DSH_BASE_URL || null,
    api: env.TERMDESK_DSH_API || null,
    apiKeyEnv: env.TERMDESK_DSH_API_KEY_ENV || null,
    displayName: env.TERMDESK_DSH_DISPLAY_NAME || provider,
    models: models.length > 0 ? models : (model ? [model] : []),
  };
}

/** Explain exactly which explicit DSH route settings are missing. */
export function routeConfigError(route) {
  const missing = [];
  if (!route.provider) missing.push('TERMDESK_CHAT_PROVIDER');
  if (!route.model) missing.push('TERMDESK_CHAT_MODEL');
  if (route.provider && route.configuredProvider && route.provider !== route.configuredProvider
      && !SELF_MOUNTED.has(route.provider)) {
    missing.push(`TERMDESK_CHAT_PROVIDER must match the selected provider "${route.provider}"`);
  }
  if (route.provider && !SELF_MOUNTED.has(route.provider)) {
    if (!route.baseURL) missing.push('TERMDESK_DSH_BASE_URL');
    if (!route.api) missing.push('TERMDESK_DSH_API');
    if (!route.apiKeyEnv) missing.push('TERMDESK_DSH_API_KEY_ENV');
    if (route.apiKeyEnv && !/^[A-Z][A-Z0-9_]*$/.test(route.apiKeyEnv)) {
      missing.push('TERMDESK_DSH_API_KEY_ENV must be an environment variable name');
    }
    if (route.baseURL) {
      let endpoint;
      try { endpoint = new URL(route.baseURL); } catch { endpoint = null; }
      if (!endpoint || !['http:', 'https:'].includes(endpoint.protocol)) {
        missing.push('TERMDESK_DSH_BASE_URL must be a valid http:// or https:// URL');
      }
    }
  }
  if (route.provider && !MODEL_ID.test(route.provider)) missing.push('TERMDESK_CHAT_PROVIDER must be a valid provider id');
  if (route.model && !MODEL_ID.test(route.model)) missing.push('TERMDESK_CHAT_MODEL must be a valid model id');
  if (route.models.some((id) => !MODEL_ID.test(id))) missing.push('TERMDESK_DSH_MODELS must contain valid model ids');
  if (missing.length === 0) return null;
  return `DSH 尚未配置可用路由（缺少 ${missing.join('、')}）。请为运行此 Agent 的设备配置 provider、model；第三方 provider 还需配置 endpoint、API 类型和凭据环境变量。`;
}

/** JSON string literals are valid YAML double-quoted scalars. */
function yamlString(value) {
  return JSON.stringify(String(value));
}

/**
 * Render the overlay. Returns null only when no overlay is needed (a provider
 * the SDK mounts by itself); incomplete third-party routes are rejected.
 */
export function renderOverlay(route) {
  const error = routeConfigError(route);
  if (error) throw new Error(error);
  if (SELF_MOUNTED.has(route.provider)) return null;
  let endpoint;
  try { endpoint = new URL(route.baseURL); } catch {
    throw new Error('DSH endpoint 必须是有效的 http:// 或 https:// URL');
  }
  if (endpoint.protocol !== 'http:' && endpoint.protocol !== 'https:') {
    throw new Error('DSH endpoint 必须使用 http:// 或 https://');
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(route.apiKeyEnv)) {
    throw new Error('DSH API key 配置必须是有效的环境变量名');
  }

  const models = [...new Set([route.model, ...route.models])];
  if (!MODEL_ID.test(route.provider)) throw new Error(`DSH provider id 无效：${route.provider}`);
  for (const id of models) {
    if (!MODEL_ID.test(id)) throw new Error(`DSH 模型 id 不能写进覆盖配置：${id}`);
  }

  const lines = [
    '# Written by TermDesk. Do not edit: it is regenerated from the route in use.',
    '# It contains only the explicit route; no model capabilities are inferred.',
    '- id: llm-pi-ai',
    "  name: '@deepseek-ai/dsh-llm-pi-ai'",
    '  config:',
    '    providers:',
    `      ${yamlString(route.provider)}:`,
    `        displayName: ${yamlString(route.displayName || route.provider)}`,
    `        apiKeyEnv: ${yamlString(route.apiKeyEnv)}`,
    `        api: ${yamlString(route.api)}`,
    `        baseURL: ${yamlString(route.baseURL)}`,
    '        models:',
  ];
  for (const id of models) {
    lines.push(`          - id: ${yamlString(id)}`);
  }
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
  const error = routeConfigError(route);
  if (error) throw new Error(error);
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
