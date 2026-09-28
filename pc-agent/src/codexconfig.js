/**
 * Codex provider configuration, edited from the phone.
 *
 * Design follows the official DeepSeek Codex integration rather than the
 * existing desktop helper:
 *   1. back up config.toml before touching it;
 *   2. write the model catalog (models.json) that declares context window,
 *      reasoning levels and tool-call format to Codex;
 *   3. rewrite only the necessary config.toml fields, preserving everything
 *      else (MCP servers, project trust levels, unrelated [desktop] keys);
 *   4. validate before writing, and abort without changing anything on failure.
 *
 * The existing codex-provider-setup.exe is deliberately NOT reused: it has
 * known defects, so this reimplements the documented contract instead.
 *
 * config.toml is edited as text, not via a TOML round-trip. A round-trip would
 * reformat and reorder the user's entire file, and Codex's config holds
 * comments and hand-written sections that must survive untouched.
 */
import fs from 'node:fs/promises';
import fsp from 'node:fs';
import path from 'node:path';
import os from 'node:os';

/**
 * Codex config directory. TERMDESK_CODEX_DIR exists so tests can run against a
 * scratch directory instead of the user's real ~/.codex — editing real agent
 * configuration must never happen as a side effect of running a test.
 */
function codexDir() {
  return process.env.TERMDESK_CODEX_DIR || path.join(os.homedir(), '.codex');
}

function configPath() {
  return path.join(codexDir(), 'config.toml');
}

function modelsPath() {
  return path.join(codexDir(), 'models.json');
}

function backupDir() {
  return path.join(codexDir(), 'termdesk-backups');
}

/** Known providers, with the fields Codex needs for each. */
export const PROVIDER_TEMPLATES = [
  {
    id: 'deepseek',
    name: 'DeepSeek 官方',
    baseUrl: 'https://api.deepseek.com/',
    wireApi: 'responses',
    models: ['deepseek-flash', 'deepseek-v4-pro'],
    contextWindow: 1048576,
    reasoningLevels: ['low', 'high', 'max'],
    defaultReasoning: 'high',
    supportsVision: false,
    visionModels: ['deepseek-flash'],
    keyPrefix: 'sk-',
  },
];

/** Read the current Codex provider configuration, without modifying anything. */
export async function readCodexConfig() {
  const result = {
    configPath: configPath(),
    modelsPath: modelsPath(),
    exists: fsp.existsSync(configPath()),
    model: null,
    modelProvider: null,
    reasoningEffort: null,
    providers: [],
    models: [],
    desktop: {},
    backups: [],
  };

  if (!result.exists) return result;

  const text = await fs.readFile(configPath(), 'utf8');

  // Top-level scalars only: stop at the first section header.
  const topLevel = text.split(/^\[/m)[0];
  result.model = matchScalar(topLevel, 'model');
  result.modelProvider = matchScalar(topLevel, 'model_provider');
  result.reasoningEffort = matchScalar(topLevel, 'model_reasoning_effort');

  // Collect [model_providers.<id>] blocks.
  const providerRe = /^\[model_providers\.([A-Za-z0-9_-]+)\]\s*$([\s\S]*?)(?=^\[|\Z)/gm;
  let m;
  while ((m = providerRe.exec(text)) !== null) {
    const body = m[2];
    result.providers.push({
      id: m[1],
      name: matchScalar(body, 'name'),
      baseUrl: matchScalar(body, 'base_url'),
      wireApi: matchScalar(body, 'wire_api'),
      // Never expose the key itself; only whether one is present.
      hasToken: /experimental_bearer_token\s*=/.test(body) || /api_key\s*=/.test(body),
    });
  }

  // Reasoning efforts declared for the desktop app.
  const desktopMatch = /^\[desktop\]\s*$([\s\S]*?)(?=^\[|\Z)/m.exec(text);
  if (desktopMatch) {
    const listMatch = /enabled-reasoning-efforts\s*=\s*\[([^\]]*)\]/.exec(desktopMatch[1]);
    if (listMatch) {
      result.desktop.enabledReasoningEfforts = listMatch[1]
        .split(',')
        .map((s) => s.trim().replace(/^["']|["']$/g, ''))
        .filter(Boolean);
    }
  }

  // Model catalog written by this app or the official script.
  if (fsp.existsSync(modelsPath())) {
    try {
      const parsed = JSON.parse(await fs.readFile(modelsPath(), 'utf8'));
      result.models = (parsed.models ?? []).map((x) => ({
        slug: x.slug,
        displayName: x.display_name ?? x.slug,
        contextWindow: x.context_window ?? null,
        maxContextWindow: x.max_context_window ?? null,
        defaultReasoning: x.default_reasoning_level ?? null,
        reasoningLevels: (x.supported_reasoning_levels ?? []).map((l) => l.effort),
        vision: (x.input_modalities ?? []).includes('image'),
      }));
    } catch {
      result.modelsError = 'models.json 不是合法 JSON';
    }
  }

  try {
    const entries = await fs.readdir(backupDir());
    result.backups = entries
      .filter((n) => n.endsWith('.toml'))
      .sort()
      .reverse()
      .slice(0, 20);
  } catch {
    // No backups yet.
  }

  return result;
}

function matchScalar(text, key) {
  const re = new RegExp(`^\\s*${key}\\s*=\\s*["']([^"']*)["']`, 'm');
  const m = re.exec(text);
  return m ? m[1] : null;
}

/** Back up config.toml and models.json before any write. */
async function backup() {
  await fs.mkdir(backupDir(), { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const made = [];
  for (const [src, label] of [[configPath(), 'config'], [modelsPath(), 'models']]) {
    if (!fsp.existsSync(src)) continue;
    const dest = path.join(backupDir(), `${label}-${stamp}.${label === 'config' ? 'toml' : 'json'}`);
    await fs.copyFile(src, dest);
    made.push(dest);
  }
  return made;
}

/** Build the DeepSeek model catalog entries, per the official integration doc. */
function buildModelEntries(template) {
  return template.models.map((slug, index) => {
    const vision = template.visionModels.includes(slug);
    const entry = {
      slug,
      prefer_websockets: false,
      support_verbosity: true,
      default_verbosity: 'low',
      apply_patch_tool_type: 'freeform',
      web_search_tool_type: 'text',
      input_modalities: vision ? ['text', 'image'] : ['text'],
      supports_image_detail_original: vision,
      truncation_policy: { mode: 'tokens', limit: 10000 },
      supports_parallel_tool_calls: true,
      tool_mode: null,
      multi_agent_version: 'v2',
      use_responses_lite: false,
      include_skills_usage_instructions: false,
      auto_review_model_override: null,
      context_window: template.contextWindow,
      max_context_window: template.contextWindow,
      effective_context_window_percent: 95,
      auto_compact_token_limit: null,
      comp_hash: '3000',
      reasoning_summary_format: 'experimental',
      default_reasoning_summary: 'none',
      display_name: slug === 'deepseek-flash' ? 'DeepSeek-Flash' : slug,
      description: `${template.name} - ${slug}`,
      default_reasoning_level: template.defaultReasoning,
      supported_reasoning_levels: template.reasoningLevels.map((effort) => ({
        effort,
        description: reasoningDescription(effort),
      })),
      shell_type: 'shell_command',
      visibility: 'list',
      minimal_client_version: '0.144.0',
      supported_in_api: true,
      availability_nux: null,
      upgrade: null,
      priority: index + 1,
      experimental_supported_tools: [],
      supports_search_tool: true,
      default_service_tier: null,
      supports_reasoning_summaries: true,
    };
    return entry;
  });
}

function reasoningDescription(effort) {
  switch (effort) {
    case 'none': return 'Disable Thinking';
    case 'low': return 'Fast responses with lighter reasoning';
    case 'high': return 'Extra high reasoning depth for complex problems';
    case 'max': return 'Maximum reasoning depth for the hardest problems';
    default: return `Reasoning level: ${effort}`;
  }
}

/**
 * Merge our model entries into an existing catalog, keeping models owned by
 * anyone else. This mirrors the official script's behaviour: only entries whose
 * slug we own are replaced.
 */
function mergeCatalog(existing, entries) {
  const owned = new Set(entries.map((e) => e.slug));
  const kept = (existing?.models ?? []).filter((m) => !owned.has(m.slug));
  return { models: [...kept, ...entries] };
}

/** Set or replace a top-level scalar in TOML text. */
function setTopLevelScalar(text, key, value) {
  const rendered = typeof value === 'string' ? `"${value}"` : String(value);
  const re = new RegExp(`^(\\s*)${key}\\s*=\\s*.*$`, 'm');
  if (re.test(text)) return text.replace(re, `$1${key} = ${rendered}`);
  // Insert before the first section header, or at the end when there is none.
  const firstSection = text.search(/^\[/m);
  const line = `${key} = ${rendered}\n`;
  if (firstSection === -1) return text + (text.endsWith('\n') ? '' : '\n') + line;
  return text.slice(0, firstSection) + line + text.slice(firstSection);
}

/** Replace or insert an entire [table] block. */
function setTable(text, tableName, body) {
  const re = new RegExp(`^\\[${tableName.replace(/\./g, '\\.')}\\]\\s*$[\\s\\S]*?(?=^\\[|\\Z)`, 'm');
  const block = `[${tableName}]\n${body.trim()}\n`;
  if (re.test(text)) return text.replace(re, block);
  return text.trimEnd() + '\n\n' + block;
}

/** Ensure the [desktop] table declares the given reasoning efforts. */
function setDesktopReasoningEfforts(text, levels) {
  const rendered = `[${levels.map((l) => `"${l}"`).join(', ')}]`;
  const desktopRe = /^\[desktop\]\s*$([\s\S]*?)(?=^\[|\Z)/m;
  const m = desktopRe.exec(text);
  if (!m) {
    return text.trimEnd() + `\n\n[desktop]\nenabled-reasoning-efforts = ${rendered}\n`;
  }
  const body = m[1];
  let newBody;
  if (/enabled-reasoning-efforts\s*=/.test(body)) {
    newBody = body.replace(/^(\s*)enabled-reasoning-efforts\s*=\s*.*$/m, `$1enabled-reasoning-efforts = ${rendered}`);
  } else {
    newBody = body.replace(/\s*$/, `\nenabled-reasoning-efforts = ${rendered}\n`);
  }
  return text.slice(0, m.index) + `[desktop]${newBody}` + text.slice(m.index + m[0].length);
}

/** Cheap structural validation: balanced brackets and parseable JSON. */
function validateToml(text) {
  const problems = [];
  const lines = text.split('\n');
  lines.forEach((line, i) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('#') || trimmed.length === 0) return;
    const open = (trimmed.match(/\[/g) ?? []).length;
    const close = (trimmed.match(/\]/g) ?? []).length;
    if (open !== close) problems.push(`第 ${i + 1} 行方括号不配对: ${trimmed.slice(0, 60)}`);
  });
  // A section header must be alone on its line.
  lines.forEach((line, i) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('[') && !/^\[[^\]]+\]$/.test(trimmed) && !trimmed.startsWith('[[')) {
      if (!/^\[\[[^\]]+\]\]$/.test(trimmed)) {
        problems.push(`第 ${i + 1} 行表头格式可疑: ${trimmed.slice(0, 60)}`);
      }
    }
  });
  return problems;
}

/**
 * Apply a provider configuration.
 *
 * @param {object} options
 * @param {string} options.providerId   key in PROVIDER_TEMPLATES
 * @param {string} options.model        model slug to select
 * @param {string} [options.apiKey]     written only when provided
 * @param {string} [options.reasoningEffort]
 * @param {number} [options.contextWindow] override for the catalog
 * @returns {Promise<object>} a structured result describing what changed
 */
export async function applyProviderConfig({
  providerId,
  model,
  apiKey,
  reasoningEffort,
  contextWindow,
}) {
  const template = PROVIDER_TEMPLATES.find((p) => p.id === providerId);
  if (!template) {
    return { ok: false, code: 'bad_provider', message: `未知服务商 "${providerId}"` };
  }
  if (!fsp.existsSync(configPath())) {
    return { ok: false, code: 'no_config', message: `找不到 ${configPath()}，请先运行一次 Codex` };
  }
  if (apiKey !== undefined && apiKey !== null && apiKey !== '' && !apiKey.startsWith(template.keyPrefix)) {
    return {
      ok: false,
      code: 'bad_key',
      message: `API Key 应以 ${template.keyPrefix} 开头`,
    };
  }

  const original = await fs.readFile(configPath(), 'utf8');
  let catalog = null;
  if (fsp.existsSync(modelsPath())) {
    try {
      catalog = JSON.parse(await fs.readFile(modelsPath(), 'utf8'));
    } catch {
      return { ok: false, code: 'bad_catalog', message: 'models.json 已损坏，拒绝覆盖' };
    }
  }

  const entries = buildModelEntries(template);
  if (Number.isInteger(contextWindow) && contextWindow > 0) {
    for (const e of entries) {
      e.context_window = contextWindow;
      e.max_context_window = contextWindow;
    }
  }

  const chosenModel = model && template.models.includes(model) ? model : template.models[0];
  const effort = reasoningEffort && template.reasoningLevels.includes(reasoningEffort)
    ? reasoningEffort
    : template.defaultReasoning;

  // --- build the new config text ---
  let next = original;
  next = setTopLevelScalar(next, 'model', chosenModel);
  next = setTopLevelScalar(next, 'model_provider', template.id);
  next = setTopLevelScalar(next, 'model_reasoning_effort', effort);
  next = setTopLevelScalar(next, 'model_catalog_json', modelsPath().replace(/\\/g, '/'));

  const providerLines = [
    `name = "${template.name}"`,
    `base_url = "${template.baseUrl}"`,
    `wire_api = "${template.wireApi}"`,
  ];
  // Only rewrite the token when a new one is supplied, so editing an unrelated
  // field never wipes an existing key.
  const existingProvider = await readCodexConfig();
  const current = existingProvider.providers.find((p) => p.id === template.id);
  if (apiKey) {
    providerLines.push(`experimental_bearer_token = "${apiKey}"`);
  } else if (current?.hasToken) {
    const tokenMatch = new RegExp(
      `^\\[model_providers\\.${template.id}\\][\\s\\S]*?(?=^\\[|\\Z)`, 'm',
    ).exec(original);
    const tk = tokenMatch && /^(\s*)(experimental_bearer_token|api_key)\s*=\s*(.+)$/m.exec(tokenMatch[0]);
    if (tk) providerLines.push(`${tk[2]} = ${tk[3]}`);
  }
  next = setTable(next, `model_providers.${template.id}`, providerLines.join('\n'));
  next = setDesktopReasoningEfforts(next, template.reasoningLevels);

  // --- validate before writing anything ---
  const problems = validateToml(next);
  if (problems.length > 0) {
    return {
      ok: false,
      code: 'invalid_toml',
      message: `生成的配置未通过校验，未做任何修改：${problems[0]}`,
    };
  }
  const mergedCatalog = mergeCatalog(catalog, entries);
  const catalogText = JSON.stringify(mergedCatalog, null, 2) + '\n';
  try {
    JSON.parse(catalogText);
  } catch {
    return { ok: false, code: 'invalid_catalog', message: '生成的模型目录不是合法 JSON，未做任何修改' };
  }

  // --- commit ---
  const backups = await backup();
  await fs.writeFile(configPath(), next, 'utf8');
  await fs.writeFile(modelsPath(), catalogText, 'utf8');

  return {
    ok: true,
    code: 'applied',
    message: `已切换到 ${template.name} · ${chosenModel}（推理强度 ${effort}）`,
    applied: {
      provider: template.id,
      model: chosenModel,
      reasoningEffort: effort,
      contextWindow: entries[0].context_window,
      wroteApiKey: Boolean(apiKey),
    },
    backups,
    configPath: configPath(),
    modelsPath: modelsPath(),
  };
}

/** Restore a previous backup, or the newest one when no name is given. */
export async function restoreBackup(name) {
  let target = name;
  if (!target) {
    try {
      const entries = (await fs.readdir(backupDir())).filter((n) => n.startsWith('config-')).sort().reverse();
      if (entries.length === 0) {
        return { ok: false, code: 'no_backup', message: '没有可用的备份' };
      }
      target = entries[0];
    } catch {
      return { ok: false, code: 'no_backup', message: '没有可用的备份' };
    }
  }

  // Refuse anything that is not a plain backup filename inside backupDir().
  if (target.includes('\\') || target.includes('/') || target.includes('..')) {
    return { ok: false, code: 'bad_name', message: '非法的备份文件名' };
  }
  const src = path.join(backupDir(), target);
  if (!fsp.existsSync(src)) {
    return { ok: false, code: 'not_found', message: `找不到备份 ${target}` };
  }

  await backup();
  await fs.copyFile(src, configPath());

  // Restore the matching models.json when the backup includes one.
  const stamp = target.replace(/^config-/, '').replace(/\.toml$/, '');
  const modelsBackup = path.join(backupDir(), `models-${stamp}.json`);
  if (fsp.existsSync(modelsBackup)) {
    await fs.copyFile(modelsBackup, modelsPath());
  }

  return { ok: true, code: 'restored', message: `已恢复备份 ${target}（当前配置也已备份）` };
}

export function codepaths() {
  return { configPath: configPath(), modelsPath: modelsPath(), backupDir: backupDir() };
}
