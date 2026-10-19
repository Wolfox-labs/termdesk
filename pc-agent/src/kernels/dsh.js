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
import { execFileSync } from 'node:child_process';

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
