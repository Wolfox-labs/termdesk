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
import { execFileSync } from 'node:child_process';

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

// ---------------------------------------------------------------------------
// 入口发现
//
// 这里原本是**三个文件各抄一遍**的同一个路径：
//   %APPDATA%\io.github.hairyf.deepseek-harness-desktop\dependencies\dsh\
//     node_modules\@deepseek-ai\dsh\lib\bin.js
// 那是旧安装器的布局，本机早就没有这个目录了；而"一处写死、抄三遍"意味着它会
// 静静地过期三次（picker 说没装、启动器却去开一个不存在的文件）。
// 现在只有这一个函数，谁要入口都问它。
// ---------------------------------------------------------------------------

/** Absolute path if it exists and is a file, else null. */
function fileOrNull(candidate) {
  if (!candidate) return null;
  try { return fs.statSync(candidate).isFile() ? candidate : null; } catch { return null; }
}

function firstFile(candidates) {
  for (const candidate of candidates) {
    const found = fileOrNull(candidate);
    if (found) return found;
  }
  return null;
}

/** First executable of that name on PATH (the POSIX/sandbox case). */
function firstOnPath(names) {
  const dirs = String(process.env.PATH || '').split(path.delimiter).filter(Boolean);
  for (const name of names) {
    for (const dir of dirs) {
      const found = fileOrNull(path.join(dir, name));
      if (found) return found;
    }
  }
  return null;
}

/** The CLI's own npm layout under a prefix: `<prefix>/node_modules/@deepseek-ai/dsh/lib/bin.js`. */
const ENTRY_TAILS = [
  path.join('@deepseek-ai', 'dsh', 'lib', 'bin.js'),
  path.join('dsh', 'lib', 'bin.js'),
];

function entryUnderPrefix(prefix) {
  if (!prefix) return null;
  return firstFile(ENTRY_TAILS.map((tail) => path.join(prefix, 'node_modules', tail)));
}

/**
 * Every prefix a global `npm i -g @deepseek-ai/dsh` could have landed in.
 *
 * The Node running this agent comes first: on a machine where Node is a portable
 * folder (no system install), `npm i -g` puts the package inside that same
 * folder, and nothing outside it records that this happened. The PATH entries
 * come last, because a global install always leaves its shim in one of them.
 */
function npmPrefixes() {
  const home = os.homedir();
  const dirs = [
    path.dirname(process.execPath),
    path.join(home, 'AppData', 'Roaming', 'npm'),
    path.join(home, 'AppData', 'Local', 'npm'),
  ];
  for (const dir of String(process.env.PATH || '').split(path.delimiter)) {
    if (dir) dirs.push(dir);
  }
  return dirs;
}

/** The layout the 0.31-era installer left behind, kept because it may still exist. */
function legacyDesktopPrefix() {
  return path.join(
    os.homedir(), 'AppData', 'Roaming', 'io.github.hairyf.deepseek-harness-desktop',
    'dependencies', 'dsh',
  );
}

const UNINSTALL_KEYS = [
  'HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall',
  'HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall',
  'HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall',
];

let installDirCache;

/**
 * Where the desktop app is installed — asked of Windows, not guessed.
 *
 * 旧安装器把 dsh 放在 `%APPDATA%\<app-id>` 下；现在这款整包装在用户挑的目录里
 * （本机是 `E:\Deepseek Harnness` —— 任何"标准位置"都猜不中它）。唯一登记了这个
 * 事实的地方是卸载项，所以问它。结果在进程内缓存：装好的程序不会在运行期间搬家。
 */
function desktopInstallDir() {
  if (installDirCache !== undefined) return installDirCache;
  installDirCache = null;
  if (process.platform !== 'win32') return installDirCache;
  for (const key of UNINSTALL_KEYS) {
    let text;
    try {
      text = decodeConsole(execFileSync('reg', ['query', key, '/s'], {
        stdio: ['ignore', 'pipe', 'ignore'],
        maxBuffer: 16 * 1024 * 1024,
      }));
    } catch {
      continue; // 这个 hive 没有或读不到：换下一个，不是错误
    }
    const found = installLocationOf(text);
    if (found) { installDirCache = found; break; }
  }
  return installDirCache;
}

/** reg.exe 按控制台代码页输出；先按 UTF-8 读，读坏了再按 GBK 读一次。 */
function decodeConsole(buffer) {
  const utf8 = buffer.toString('utf8');
  if (!utf8.includes('\uFFFD')) return utf8;
  try { return new TextDecoder('gbk').decode(buffer); } catch { return utf8; }
}

/** Pull `InstallLocation` out of the block whose `DisplayName` names the app. */
function installLocationOf(text) {
  for (const block of String(text).split(/\r?\n(?=HKEY_)/)) {
    if (!/DisplayName\s+REG_SZ\s+.*DeepSeek Harness/i.test(block)) continue;
    const line = block.match(/InstallLocation\s+REG_SZ\s+(.+)/i);
    if (line) return line[1].trim().replace(/[\\/]+$/, '');
  }
  return null;
}

let whyMissing = null;

/**
 * Why there is no entry, when there is something worth saying.
 *
 * "未在本机找到" and "装了，但引擎在 app.asar 里" are different facts, and the
 * second one is actionable — so the picker is told which of the two it is.
 */
export function dshWhyMissing() {
  return whyMissing;
}

/**
 * The JS entry for the DSH runtime, or null when there is none to run.
 *
 * The pipeline launches it as `node <entry> --profile sdk` (chat.js:1779), so
 * this only ever answers with a file plain node can execute — a `.cmd` shim is
 * not an answer, however close it looks on disk.
 *
 * `TERMDESK_DSH` wins outright: an explicit override is the one thing that must
 * not be second-guessed.
 */
export function findDshEntry() {
  whyMissing = null;
  if (process.env.TERMDESK_DSH) return process.env.TERMDESK_DSH;
  if (process.platform !== 'win32') {
    // 沙盒里的 dsh 就是 PATH 上的一个命令；POSIX 主机同理。
    const onPath = firstOnPath(['dsh']);
    if (onPath) return onPath;
  }
  for (const prefix of npmPrefixes()) {
    const found = entryUnderPrefix(prefix);
    if (found) return found;
  }
  const legacy = entryUnderPrefix(legacyDesktopPrefix());
  if (legacy) return legacy;

  const install = desktopInstallDir();
  if (install) {
    // 新安装器把 CLI 放在 resources 下；哪种形状取决于它有没有把引擎解包出来。
    const unpacked = entryUnderPrefix(path.join(install, 'resources', 'app.asar.unpacked', 'dsh'));
    if (unpacked) return unpacked;
    const beside = entryUnderPrefix(path.join(install, 'resources', 'dsh'));
    if (beside) return beside;
    whyMissing = `桌面版装在 ${install}，但引擎打包在 app.asar 里，需要它自己的 Electron 启动器（本版未支持）`;
  }
  return null;
}
