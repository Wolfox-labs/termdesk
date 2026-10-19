/**
 * Kernel registry — the single source of truth for "what can this PC run".
 *
 * Before this file, discovery (engines.js), the chat pipeline (chat.js) and the
 * phone's picker each carried their own idea of which engines existed, so they
 * drifted. Now there is one table, and everything else asks it.
 *
 * Tiers are honest by construction, because the phone offers exactly what the
 * tier says:
 *
 *   native        an adapter is wired straight into the chat pipeline
 *                 (Codex app-server, DSH SDK)
 *   acp           the kernel serves ACP; kernels/acp.js drives it — one adapter
 *                 for every product that speaks the protocol
 *   shim          Claude-Code-shaped CLI: non-interactive print mode, its own
 *                 resume flag, NDJSON output. The spawn contract is recorded
 *                 here; the last step (one live run) is not done yet.
 *   unsupported   installed, but exposes no programmable interface at all
 *
 * Adding a kernel is a data edit, not a code change:
 *   - an ACP kernel: one entry with tier 'acp'
 *   - a local/experimental kernel: no edit at all — export
 *     TERMDESK_ACP_KERNELS='[{"id":"mine","label":"Mine","bin":"C:\\\\x.exe","args":["acp"]}]'
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { AcpKernel } from './acp.js';
import { fileExists, isAsarPath } from './asar.js';
import { manifestFor } from './manifest.js';
import { resolveDshRuntime } from './dsh-runtime.js';
import { findNpmCommand, findPathExecutable } from './discovery.js';

const NPM_ROOT = path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'npm');
const LOCAL_PROGRAMS = path.join(os.homedir(), 'AppData', 'Local', 'Programs');

/**
 * Absolute path if it exists and is a file, else null.
 *
 * Asar-aware: the bundled DSH entry lives inside the desktop app's archive, where
 * `fs.statSync` cannot see it but the file is really there (see kernels/asar.js).
 */
function fileOrNull(candidate) {
  if (!candidate) return null;
  if (isAsarPath(candidate)) return fileExists(candidate) ? candidate : null;
  try { return fs.statSync(candidate).isFile() ? candidate : null; } catch { return null; }
}

/** First existing candidate. */
function firstFile(candidates) {
  for (const candidate of candidates) {
    const found = fileOrNull(candidate);
    if (found) return found;
  }
  return null;
}

/** First executable of that name on PATH. */
function firstOnPath(names, rejections = [], { env = process.env, platform = process.platform } = {}) {
  const dirs = (env.PATH || "").split(path.delimiter).filter(Boolean);
  for (const name of names) {
    const candidates = platform === 'win32' && !path.extname(name)
      ? [name, ...(env.PATHEXT || '.EXE;.CMD;.BAT').split(';').filter(Boolean).map((ext) => name + ext.toLowerCase())]
      : [name];
    for (const dir of dirs) {
      for (const candidate of candidates) {
        const found = executableOrNull(path.join(dir, candidate), rejections);
        if (found) return found;
      }
    }
  }
  return null;
}

/** A command must exist and be executable on this host, not just have a name. */
function executableOrNull(candidate, rejections = []) {
  const found = fileOrNull(candidate);
  if (!found) return null;
  const reject = (reason) => { rejections.push({ path: found, reason }); return null; };
  if (/\.(cmd|bat)$/i.test(found)) return reject('脚本类型不支持（.cmd/.bat）；请登记原生可执行文件或 Node 脚本入口');
  if (process.platform !== 'win32') {
    try { fs.accessSync(found, fs.constants.X_OK); } catch { return reject('文件没有执行权限'); }
    // An executable bit on a copied Windows binary does not make it runnable
    // inside the Android/Linux sandbox.
    let fd;
    try {
      fd = fs.openSync(found, 'r');
      const header = Buffer.alloc(2);
      fs.readSync(fd, header, 0, 2, 0);
      if (header.toString('ascii') === 'MZ') return reject('Windows 可执行文件无法在当前系统运行');
    } catch { return null; } finally { if (fd !== undefined) fs.closeSync(fd); }
  }
  return found;
}

function resolveExecutable(candidate, rejections = []) {
  if (!candidate || typeof candidate !== 'string') return null;
  if (path.isAbsolute(candidate) || candidate.includes('/') || candidate.includes('\\')) return executableOrNull(candidate, rejections);
  return firstOnPath([candidate], rejections);
}

/**
 * Node-hosted CLIs (npm global installs) are spawned as
 * `node <entry> <args…>`. `process.execPath` is used rather than a bare `node`
 * so a stripped PATH (scheduled task, service) cannot break the launch.
 */
/** Resolve OpenCode without assuming a particular drive or vendor folder. */
export function resolveOpenCode(rejections = [], {
  env = process.env,
  platform = process.platform,
  npmRoot = NPM_ROOT,
  localPrograms = LOCAL_PROGRAMS,
} = {}) {
  if (env.TERMDESK_OPENCODE) {
    return fileOrNull(env.TERMDESK_OPENCODE) ?? env.TERMDESK_OPENCODE;
  }
  if (platform !== 'win32') return firstOnPath(['opencode'], rejections, { env, platform });

  const native = findPathExecutable('opencode', { env, platform, rejected: rejections });
  if (native) return native;
  const npm = findNpmCommand('opencode', { env, platform, packages: ['opencode-ai'], prefixes: [npmRoot], rejected: rejections });
  if (npm) return { bin: npm.bin, args: npm.args };

  return firstFile([
    path.join(localPrograms, '@opencode-aidesktop', 'resources', 'bin', 'opencode-cli.exe'),
  ]);
}

function resolvePathOrNpm(command, rejections, packages = []) {
  if (process.platform !== 'win32') return firstOnPath([command], rejections);
  const native = findPathExecutable(command, { rejected: rejections });
  if (native) return native;
  const npm = findNpmCommand(command, { packages, rejected: rejections });
  return npm ? { bin: npm.bin, args: npm.args } : null;
}

/**
 * Resolve DSH through `kernels/dsh-runtime.js`.
 *
 * The entry may live inside the desktop app's `app.asar`, in which case the
 * command is the app's own Electron binary in `ELECTRON_RUN_AS_NODE` mode — the
 * only Node that can read the archive. `env` is part of the descriptor so the
 * chat pipeline starts exactly what discovery found.
 */
function resolveDsh(rejections = []) {
  const runtime = resolveDshRuntime({ rejected: rejections });
  if (!runtime) return null;
  return {
    bin: runtime.command,
    args: [...(runtime.prefixArgs ?? [])],
    script: runtime.script ?? null,
    env: runtime.env ?? {},
    source: runtime.source,
  };
}

/**
 * Kernel table.
 *
 * `resolve()` returns either a plain path (spawned directly) or
 * `{ bin, args }` (spawned with a prelude, e.g. node + script).
 */
function table() {
  return [
    {
      id: 'codex',
      label: 'Codex',
      tier: 'native',
      transport: 'app-server',
      resume: true,
      // The model comes from config.toml, which codex.get / codex.apply manage:
      // overriding it here would disagree with that page.
      modelRoute: 'kernel-config',
      // The only kernel that can narrate a session in flight (see the descriptor).
      liveTerminals: true,
      detail: '官方 app-server：列表 / 读取 / 恢复 / fork / 打断都由内核提供',
      resolve: (rejections) => {
        if (process.env.TERMDESK_CODEX) return fileOrNull(process.env.TERMDESK_CODEX) ?? process.env.TERMDESK_CODEX;
        if (process.platform !== "win32") return firstOnPath(["codex"], rejections);
        // ChatManager passes this exact command (including Node prelude) to
        // CodexAppServer, so discovery and actual startup use one contract.
        const discovered = resolvePathOrNpm('codex', rejections, ['@openai/codex']);
        if (discovered) return discovered;
        const base = path.join(os.homedir(), 'AppData', 'Local', 'OpenAI', 'Codex', 'bin');
        try {
          for (const entry of fs.readdirSync(base)) {
            const found = fileOrNull(path.join(base, entry, 'codex.exe'));
            if (found) return found;
          }
        } catch { /* fall through */ }
        return 'codex';
      },
    },
    {
      id: 'claude-code',
      label: 'Claude Code',
      tier: 'unsupported',
      transport: null,
      detectOnly: true,
      resume: false,
      detail: '已检测到 Claude Code 安装；TermDesk 尚未接入其 CLI 协议，暂不可选择',
      resolve: (rejections) => {
        if (process.env.TERMDESK_CLAUDE) return resolveExecutable(process.env.TERMDESK_CLAUDE, rejections);
        return resolvePathOrNpm('claude', rejections, ['@anthropic-ai/claude-code']);
      },
    },
    {
      id: 'dsh',
      label: 'DeepSeek Harness',
      tier: 'native',
      transport: 'sdk',
      modelRoute: 'default',
      // The SDK runtime can reopen a persisted session: `session/resume` loads the
      // stored history with the model route chosen at initialize, and the next
      // prompt continues that same conversation. Implemented in ChatManager.resumeDsh
      // and driven by the phone's "继续对话". One honest limit stays in the note:
      // the desktop app may be holding the same session, and a resume cannot ask it.
      resume: true,
      detail: 'SDK 运行时：可连续对话；可恢复本机保存的会话（桌面端同时打开时两边会各写一份）',
      resolve: resolveDsh,
    },
    {
      id: 'opencode',
      label: 'OpenCode',
      tier: 'acp',
      transport: 'acp',
      modelRoute: 'pinned',
      acpArgs: ['acp'],
      resume: true,
      detail: 'ACP：会话列表 / 恢复 / fork / 图片与内嵌上下文',
      resolve: resolveOpenCode,
    },
    {
      id: 'mimo',
      label: 'MiMo Code',
      tier: 'acp',
      transport: 'acp',
      modelRoute: 'pinned',
      acpArgs: ['acp'],
      resume: true,
      detail: 'ACP（OpenCode 内核）：会话列表 / 恢复 / fork',
      resolve: (rejections) => {
        if (process.env.TERMDESK_MIMO) return fileOrNull(process.env.TERMDESK_MIMO) ?? process.env.TERMDESK_MIMO;
        return resolvePathOrNpm('mimo', rejections, ['@mimo-ai/cli']);
      },
    },
    {
      id: 'qoder',
      label: 'QoderWork CN',
      tier: 'shim',
      // The CLI can continue a session by id (--resume / --session), so the
      // phone may offer "继续对话" - but no transcript body, which the chat
      // pipeline says out loud instead of showing an empty conversation.
      resume: true,
      transport: 'cli',
      modelRoute: 'pinned',
      detail: 'CLI 形状：协议已用真实 CLI 输出核对（v1.1.26），但本机 CLI 尚未登录，所以还不能跑通一轮',
      shim: {
        // Measured against qoderclicn 1.1.26, not the 0.15.x help text first
        // recorded: -p prints and exits, -o accepts text|json|stream-json, and
        // stream-json is NDJSON with type system|assistant|result. The answer
        // lives at message.content[0].text while it streams and at result.result
        // on the final line - emitting both would show it twice, so `result` is
        // only used when nothing streamed.
        newArgs: ['--print', '--output-format', 'stream-json'],
        resumeArgs: ['--print', '--output-format', 'stream-json', '--resume'],
        prompt: 'argv',
        sessionId: 'session_id',
        textPaths: ['message.content'],
        resultPaths: ['result'],
        errorPaths: ['error'],
        errorTextPaths: ['result'],
        // -m/--model exists, so a model choice is real for this kernel.
        modelFlag: '--model',
        // --list-sessions answers in human text ("No previous sessions found
        // for this project"), which this adapter does not parse yet, so it does
        // not claim a session index. A small parser is the follow-up (方案 §8.2).
        listParsed: false,
        replay: false,
        verified: false,
      },
      resolve: (rejections) => fileOrNull(process.env.TERMDESK_QODER)
        ?? resolvePathOrNpm('qoderclicn', rejections, ['qoder'])
        ?? (process.platform !== "win32" ? firstOnPath(["qoder"], rejections)
          : firstFile([path.join(LOCAL_PROGRAMS, "QoderWork CN", "resources", "bin", "qoderclicn.exe")])),
    },
    {
      // Command Code grew a real ACP server in 1.74.1 (`cmd acp`), so it is driven
      // by the same adapter as OpenCode instead of by a CLI shim: the kernel itself
      // hands back the session index, replays a past transcript and accepts images -
      // none of which a shim can do. The shim manifest this entry used to carry was
      // also wrong in two places (a real turn puts the session id at `event.sessionId`
      // and the answer in `finalText` / `text_delta.delta`), which is exactly why a
      // shim stays a guess until it is promoted to a protocol.
      id: 'command-code',
      label: 'Command Code',
      tier: 'acp',
      transport: 'acp',
      modelRoute: 'pinned',
      // --no-auto-update matters here: this CLI updates itself in the
      // background (it did so mid-session on 2026-10-05, 1.65.0 -> 1.74.1), and
      // an npm install rewriting the package under a live ACP server kills the
      // kernel process - which the phone sees as a failed conversation.
      acpArgs: ['acp', '--no-auto-update'],
      resume: true,
      detail: 'ACP（1.74.1 原生）：会话列表 / 读取回放 / 恢复 / 图片与内嵌上下文',
      resolve: (rejections) => {
        if (process.env.TERMDESK_COMMAND_CODE) return fileOrNull(process.env.TERMDESK_COMMAND_CODE) ?? process.env.TERMDESK_COMMAND_CODE;
        if (process.platform !== 'win32') return firstOnPath(['cmdc', 'commandcode', 'command-code'], rejections);
        return resolvePathOrNpm('command-code', rejections, ['command-code']);
      },
    },
  ];
}

/** Extra ACP kernels from the environment, so a new one needs no code change. */
function envKernels(raw = process.env.TERMDESK_ACP_KERNELS) {
  if (!raw) return [];
  let parsed;
  try { parsed = JSON.parse(raw); } catch { return []; }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((k) => k && typeof k.id === 'string' && typeof k.bin === 'string')
    .map((k) => ({
      id: k.id,
      label: typeof k.label === 'string' ? k.label : k.id,
      tier: 'acp',
      transport: 'acp',
      modelRoute: 'pinned',
      acpArgs: Array.isArray(k.args) ? k.args : ['acp'],
      // ACP by construction, and ACP declares session capabilities, so the
      // default is "it can reopen a past conversation" unless the entry says
      // otherwise. (`resume: Boolean(entry.resume)` would have silently disabled
      // it for every kernel added through the environment.)
      resume: k.resume !== false,
      detail: '来自 TERMDESK_ACP_KERNELS 的 ACP 内核',
      // `preArgs` is the prelude a node-hosted CLI needs (node + script);
      // without it the flags would go to the runtime instead of the CLI.
      resolve: (rejections) => (Array.isArray(k.preArgs) && k.preArgs.length
        ? { bin: fileOrNull(k.bin) ?? k.bin, args: k.preArgs }
        : (fileOrNull(k.bin) ?? k.bin)),
    }));
}

/**
 * Extra CLI kernels from the environment, symmetric with TERMDESK_ACP_KERNELS.
 *
 * A CLI kernel needs a manifest, so the environment carries one. Kernels added
 * this way are trusted by default (the declarer states the contract) unless the
 * entry says `verified: false`.
 */
function envCliKernels(raw = process.env.TERMDESK_CLI_KERNELS) {
  if (!raw) return [];
  let parsed;
  try { parsed = JSON.parse(raw); } catch { return []; }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((k) => k && typeof k.id === 'string' && typeof k.bin === 'string')
    .map((k) => ({
      id: k.id,
      label: k.label ?? k.id,
      tier: 'shim',
      transport: 'cli',
      modelRoute: 'pinned',
      resume: k.resume !== false,
      detail: k.detail ?? '来自 TERMDESK_CLI_KERNELS 的 CLI 内核',
      shim: {
        prompt: 'argv',
        sessionId: 'session_id',
        text: 'text',
        replay: false,
        verified: k.verified !== false,
        ...(k.shim ?? {}),
      },
      // `preArgs` is the prelude a node-hosted CLI needs (node + script);
      // without it the flags would go to the runtime instead of the CLI.
      resolve: (rejections) => (Array.isArray(k.preArgs) && k.preArgs.length
        ? { bin: fileOrNull(k.bin) ?? k.bin, args: k.preArgs }
        : (fileOrNull(k.bin) ?? k.bin)),
    }));
}

/** Optional installed-kernel registrations; adding one needs no brand-name edit. */
function fileKernels() {
  const directory = process.env.TERMDESK_KERNEL_MANIFEST_DIR || path.join(os.homedir(), '.termdesk', 'kernels');
  let files;
  try { files = fs.readdirSync(directory).filter((name) => name.endsWith('.json')).sort(); } catch { return []; }
  const items = [];
  for (const name of files) {
    const location = path.join(directory, name);
    try {
      const value = JSON.parse(fs.readFileSync(location, 'utf8'));
      for (const item of Array.isArray(value) ? value : [value]) {
        if (!item || typeof item.id !== 'string' || !item.id.trim() || typeof item.bin !== 'string' || !item.bin.trim()) {
          console.warn('[kernels] ' + location + ': 配置缺少有效的 id / bin，已跳过');
        } else if (item.transport && !['acp', 'cli'].includes(item.transport)) {
          console.warn('[kernels] ' + location + ': 不支持的 transport，已跳过');
        } else items.push(item);
      }
    } catch (error) { console.warn('[kernels] ' + location + ': 无法读取助手配置（' + error.message + '）'); }
  }
  return [
    ...envKernels(JSON.stringify(items.filter((item) => !item.transport || item.transport === 'acp'))),
    ...envCliKernels(JSON.stringify(items.filter((item) => item.transport === 'cli'))),
  ].map((entry) => ({ ...entry, detail: '检测到本机接入配置中的助手' }));
}

/**
 * A path the person chose for a kernel, kept beside the agent's own state.
 *
 * Auto-discovery is a guess about where an installer put something; it is a good
 * guess and often wrong. Overriding the path is how a console fixes that without
 * this project learning every vendor's install layout, and it is deliberately
 * NOT an install: nothing is copied, downloaded or executed to record it.
 */
function kernelPathFile() {
  return process.env.TERMDESK_KERNEL_PATHS || path.join(os.homedir(), '.termdesk', 'kernel-paths.json');
}

function kernelManifestDir() {
  return process.env.TERMDESK_KERNEL_MANIFEST_DIR || path.join(os.homedir(), '.termdesk', 'kernels');
}

function readKernelPaths() {
  try {
    const parsed = JSON.parse(fs.readFileSync(kernelPathFile(), 'utf8'));
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    return Object.fromEntries(
      Object.entries(parsed).filter(([id, value]) => typeof id === 'string' && typeof value === 'string' && value.trim()),
    );
  } catch {
    return {};
  }
}

function writeKernelPaths(paths) {
  const file = kernelPathFile();
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(paths, null, 2) + '\n', { mode: 0o600 });
}

function allEntries() {
  const overrides = readKernelPaths();
  const entries = [...table(), ...fileKernels(), ...envKernels(), ...envCliKernels()];
  const deduped = [...new Map(entries.map((entry) => [entry.id, entry])).values()];
  return deduped.map((entry) => {
    const override = overrides[entry.id];
    if (!override) return entry;
    return {
      ...entry,
      // Keep the discoverer around: the console offers "恢复自动检测", and that
      // needs the answer the override replaced, not a second copy of the search.
      pathOverride: override,
      autoResolve: entry.resolve,
      resolve: () => override,
    };
  });
}

// ---- management: enable/disable, register, remove ----------------------------
//
// The console is allowed to change what this machine offers without installing
// or uninstalling the agent itself. Disabling is a local preference; registering
// writes a manifest into the same directory fileKernels() already reads, so the
// discovery path stays the single source of truth.

function kernelStateFile() {
  return process.env.TERMDESK_KERNEL_STATE || path.join(os.homedir(), '.termdesk', 'kernel-state.json');
}

function readKernelState() {
  try {
    const parsed = JSON.parse(fs.readFileSync(kernelStateFile(), 'utf8'));
    return { disabled: Array.isArray(parsed?.disabled) ? parsed.disabled.filter((x) => typeof x === 'string') : [] };
  } catch {
    return { disabled: [] };
  }
}

function writeKernelState(state) {
  const file = kernelStateFile();
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
}

export function setKernelEnabled(id, enabled) {
  const known = allEntries().find((entry) => entry.id === id);
  if (!known) return { ok: false, code: 'unknown_kernel', message: `没有这个内核："${id}"` };
  const state = readKernelState();
  const set = new Set(state.disabled);
  if (enabled) set.delete(id); else set.add(id);
  writeKernelState({ ...state, disabled: [...set] });
  return { ok: true, code: enabled ? 'enabled' : 'disabled', message: enabled ? `已启用 ${known.label ?? id}` : `已禁用 ${known.label ?? id}（不会卸载它）` };
}

export function registerKernel(entry = {}) {
  const id = String(entry.id ?? '').trim();
  if (!/^[a-z0-9][a-z0-9_-]{1,40}$/.test(id)) {
    return { ok: false, code: 'bad_id', message: '标识只能用小写字母、数字、_ 和 -，2–41 个字符' };
  }
  if (table().some((item) => item.id === id)) {
    return { ok: false, code: 'builtin', message: `"${id}" 是内置内核，不能覆盖；换一个标识` };
  }
  const label = String(entry.label ?? '').trim();
  const bin = String(entry.bin ?? '').trim();
  if (!label) return { ok: false, code: 'bad_label', message: '需要名称' };
  if (!bin) return { ok: false, code: 'bad_bin', message: '需要可执行文件路径或命令名' };
  const args = Array.isArray(entry.args) ? entry.args.map((a) => String(a)) : [];
  const transport = ['acp', 'cli'].includes(entry.transport) ? entry.transport : 'acp';
  const manifest = {
    id,
    label,
    bin,
    args,
    transport,
    tier: transport === 'acp' ? 'acp' : 'shim',
    detail: String(entry.detail ?? '由控制台注册').slice(0, 200),
  };
  const file = path.join(kernelManifestDir(), `${id}.json`);
  if (fs.existsSync(file)) return { ok: false, code: 'exists', message: `已经有一个自定义内核叫 "${id}"` };
  fs.mkdirSync(kernelManifestDir(), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600 });
  return { ok: true, code: 'registered', message: `已注册 ${label}`, manifest };
}

export function removeKernel(id) {
  const file = path.join(kernelManifestDir(), `${id}.json`);
  if (!fs.existsSync(file)) {
    return { ok: false, code: 'not_custom', message: `"${id}" 不是控制台注册的内核；内置内核只能禁用` };
  }
  fs.rmSync(file, { force: true });
  const state = readKernelState();
  writeKernelState({ ...state, disabled: state.disabled.filter((x) => x !== id) });
  return { ok: true, code: 'removed', message: `已移除自定义内核 ${id}` };
}

/**
 * Point a kernel at another executable.
 *
 * Built-in kernels keep the answer as an override next to the agent's own state;
 * a console-registered kernel keeps it in its own manifest, because the manifest
 * IS its definition. Validation is the same check discovery uses, so a path that
 * is saved is a path that can actually be spawned.
 */
export function setKernelPath(id, value) {
  const entry = allEntries().find((item) => item.id === id);
  if (!entry) return { ok: false, code: 'unknown_kernel', message: `没有这个内核："${id}"` };
  const target = String(value ?? '').trim();
  if (!target) return { ok: false, code: 'bad_path', message: '路径不能为空' };
  if (!path.isAbsolute(target)) {
    return { ok: false, code: 'bad_path', message: '需要完整路径，例如 C:\\Program Files\\...\\codex.exe' };
  }
  const rejections = [];
  const resolved = resolveExecutable(target, rejections);
  if (!resolved) {
    const why = rejections[0]?.reason ?? '这个路径不存在，或不是可执行文件';
    return { ok: false, code: 'bad_path', message: why };
  }
  const manifest = path.join(kernelManifestDir(), `${id}.json`);
  if (fs.existsSync(manifest)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(manifest, 'utf8'));
      fs.writeFileSync(manifest, JSON.stringify({ ...parsed, bin: resolved }, null, 2) + '\n', { mode: 0o600 });
      ACP_PROBE_CACHE.delete(id);
      return { ok: true, code: 'saved', message: `${entry.label ?? id} 改用 ${resolved}` };
    } catch (error) {
      return { ok: false, code: 'write_failed', message: `写不了清单：${error.message}` };
    }
  }
  const paths = readKernelPaths();
  paths[id] = resolved;
  try {
    writeKernelPaths(paths);
  } catch (error) {
    return { ok: false, code: 'write_failed', message: `写不了路径表：${error.message}` };
  }
  ACP_PROBE_CACHE.delete(id);
  return { ok: true, code: 'saved', message: `${entry.label ?? id} 改用 ${resolved}（没有复制或安装任何文件）` };
}

/** Drop the override and go back to discovery. */
export function clearKernelPath(id) {
  const paths = readKernelPaths();
  if (!(id in paths)) {
    return { ok: false, code: 'no_override', message: '这个内核本来就用自动检测到的位置' };
  }
  delete paths[id];
  writeKernelPaths(paths);
  ACP_PROBE_CACHE.delete(id);
  return { ok: true, code: 'cleared', message: '已恢复自动检测路径' };
}

/** Where management state lives, so the console can say it instead of implying it. */
export function kernelStorePaths() {
  return { paths: kernelPathFile(), manifests: kernelManifestDir(), state: kernelStateFile() };
}


/**
 * Resolved command for an entry: `{ bin, args, script }` or null when not
 * installed. `script` is the user-meaningful file when the real executable is
 * only a runtime host (node + an npm entry), so the picker shows the kernel
 * rather than "node.exe".
 */
export function kernelCommand(entry, rejections = []) {
  const resolved = entry.resolve(rejections);
  if (!resolved) return null;
  if (typeof resolved === 'string' && entry.transport === 'sdk' && /\.[cm]?js$/i.test(resolved)) {
    // DSH's JS entry is launched through Node; the script needs to be readable,
    // not executable. Check the runtime and script independently.
    const script = fileOrNull(resolved);
    const runtime = executableOrNull(process.execPath, rejections);
    return script && runtime ? { bin: runtime, args: [script], script, env: {} } : null;
  }
  const bin = resolveExecutable(typeof resolved === 'string' ? resolved : resolved.bin, rejections);
  if (!bin) return null;
  if (typeof resolved === 'string') return { bin, args: [], script: null, env: {} };
  const args = [...(resolved.args ?? [])];
  // A descriptor may name its script separately from the launcher (Electron host +
  // entry inside an archive). Put it back in argv, where both Node and Electron
  // expect it, so every consumer sees one spawn shape.
  if (resolved.script && args[0] !== resolved.script) args.unshift(resolved.script);
  const script = args[0] && !args[0].startsWith('-') ? args[0] : null;
  if (script && !fileOrNull(script)) return null;
  return { bin, args, script, env: resolved.env ?? {} };
}

/**
 * Resolved kernel descriptors, ready for the phone's picker.
 *
 * `selectable` is the honest gate: it is true only when a chat can actually run
 * on the kernel today. A kernel that is installed but whose adapter is missing
 * says exactly that in `detail`, instead of being offered and then failing.
 */
/** A shim manifest is only trusted once a real CLI run has confirmed it. */
function shimVerified(entry) {
  if (!entry?.shim) return false;
  if (entry.shim.verified) return true;
  const forced = String(process.env.TERMDESK_SHIM_VERIFIED ?? '').split(',').map((s) => s.trim());
  return forced.includes(entry.id);
}

export function listKernels({ installedOnly = false, includeUnsupported = false } = {}) {
  const disabled = new Set(readKernelState().disabled);
  const customIds = new Set(fileKernels().map((entry) => entry.id));
  const descriptors = allEntries()
    // A desktop chat app with no programmable interface is not a kernel, and
    // listing it in a console is how a list of "what can run here" stops being
    // true. Nothing in the table is unsupported now; the filter stays for a
    // manifest that declares it, so such an entry is never offered as a kernel.
    .filter((entry) => includeUnsupported || entry.tier !== 'unsupported' || entry.detectOnly === true)
    .map((entry) => {
    const rejections = [];
    const command = kernelCommand(entry, rejections);
    const manifest = manifestFor(entry);
    const available = Boolean(command);
    const enabled = !disabled.has(entry.id);
    const probe = entry.tier === 'acp' && available ? validProbeCache(entry.id, entry) : null;
    const probeFailed = probe?.ok === false;
    // What discovery would have found, even when an override won: the console
    // shows it beside the override so "恢复自动检测" is not a blind action.
    const autoCommand = entry.pathOverride
      ? kernelCommand({ ...entry, resolve: entry.autoResolve })
      : null;
    return {
      id: entry.id,
      label: entry.label,
      tier: entry.tier,
      transport: entry.transport,
      available,
      /** A local preference, not an install state: disabling hides it from chat
       *  selection without uninstalling the agent. */
      enabled,
      /** Registered through the console (a manifest in ~/.termdesk/kernels). */
      custom: customIds.has(entry.id),
      /** True when the path below was chosen by hand instead of discovered. */
      override: Boolean(entry.pathOverride),
      /** Where discovery would look, kept for the "恢复自动检测" row. */
      autoPath: entry.pathOverride ? (autoCommand?.script ?? autoCommand?.bin ?? null) : null,
      // A shim is selectable only once its manifest has been confirmed by a
      // real run: an unverified manifest is a guess about argv and about which
      // JSON key carries the answer, and offering it would be offering a kernel
      // that may well answer nothing.
      selectable: available && enabled && !probeFailed && (
        entry.tier === 'native' || entry.tier === 'acp' || (entry.tier === 'shim' && shimVerified(entry))
      ),
      path: command ? (command.script ?? command.bin) : null,
      args: command?.args ?? null,
      /** The exact line this agent would spawn, so the console can copy it. */
      command: (() => {
        const spec = spawnSpec(entry.id);
        if (!spec) return null;
        const quote = (part) => (/\s/.test(part) ? `"${part}"` : part);
        return [quote(spec.bin), ...spec.args.map(quote)].join(' ');
      })(),
      detail: !enabled ? '已在这台电脑禁用（可随时重新启用）' : (probe ? probe.detail : (available ? (entry.tier === 'acp' ? '已找到 ACP 启动入口；协议能力尚未握手验证' : entry.detail) : (rejections[0] ? rejections[0].reason + '：' + rejections[0].path : (entry.tier === 'unsupported' ? '未安装' : '未在本机找到')))),
      ...(probe?.acp ? { acp: probe.acp } : {}),
      shim: entry.shim ?? null,
      multiTurn: entry.tier === 'native' || entry.tier === 'acp' || (entry.tier === 'shim' && shimVerified(entry)),
      progress: entry.tier === 'native' || entry.tier === 'acp' || entry.tier === 'shim',
      // Per kernel, NOT per tier: "native" describes how it is driven, not
      // whether it can reopen a past conversation. DSH is native and still has
      // no verified resume, and saying otherwise is what makes a phone offer a
      // button that cannot work.
      resume: manifest.session.resume,
      // Whether a LIVE session can report work it is doing right now. Asked by
      // "进行中", whose promise is "what is still running on the PC": only the
      // app-server kernel exposes that, and asking the others buys a round trip
      // that answers nothing.
      liveTerminals: Boolean(entry.liveTerminals),
      // Where a new conversation's default model comes from. Declared per kernel
      // because the three answers are genuinely different - see kernels/contract.js.
      modelRoute: entry.modelRoute ?? 'default',
      manifest,
    };
  });
  return installedOnly ? descriptors.filter((kernel) => kernel.available) : descriptors;
}

/** One descriptor by id, or null. */
export function getKernel(id) {
  return listKernels().find((k) => k.id === id) ?? null;
}

/** Engine ids a chat can be created on right now. */
export function chatEngineIds() {
  return listKernels().filter((k) => k.selectable).map((k) => k.id);
}

/** ACP kernels that are installed. */
export function acpKernels() {
  return listKernels().filter((k) => k.tier === 'acp' && k.available);
}

/** `{ bin, args, env? }` for one kernel id, with cwd applied by the caller. */
export function spawnSpec(id) {
  const entry = allEntries().find((k) => k.id === id);
  if (!entry) return null;
  const command = kernelCommand(entry);
  if (!command) return null;
  const env = command.env && Object.keys(command.env).length > 0 ? command.env : null;
  const base = entry.tier === 'acp'
    ? { bin: command.bin, args: [...command.args, ...(entry.acpArgs ?? ['acp'])] }
    : { bin: command.bin, args: command.args };
  return env ? { ...base, env } : base;
}

/** The recorded CLI contract for a shim kernel, or null. */
export function shimSpec(id) {
  const entry = allEntries().find((k) => k.id === id);
  return entry?.shim ?? null;
}

export function kernelTier(id) {
  return allEntries().find((k) => k.id === id)?.tier ?? null;
}

/** True when the kernel is driven by the shared ACP adapter. */
/** Kernels driven by the CLI shim adapter. */
export function isCliKernel(id) {
  const entry = allEntries().find((k) => k.id === id);
  return entry?.tier === 'shim' && Boolean(entry.shim);
}

/** True for every kernel the chat pipeline can drive through an adapter. */
export function isAdapterKernel(id) {
  return isAcpKernel(id) || isCliKernel(id);
}

export function isAcpKernel(id) {
  return kernelTier(id) === 'acp';
}

/**
 * The kernel table, optionally with a live ACP handshake.
 *
 * A plain [listKernels] answer is filesystem truth: is the binary there, and
 * what tier is it. With `TERMDESK_KERNELS_PROBE=1` each installed ACP kernel is
 * additionally asked to shake hands, through the SAME adapter the chat pipeline
 * uses — so the picker can never advertise a capability the chat path lacks.
 * Off by default because it spawns processes, and a startup banner should not.
 *
 * This used to live in engines.js as `probeEngines`, on the deprecated task
 * pipeline; it moved here when that surface was deleted, because the kernel
 * table is the registry's business.
 */
const ACP_PROBE_TTL_MS = 10 * 60_000;
const ACP_PROBE_CACHE = new Map();

function probeSignature(entry) {
  return JSON.stringify({ spec: spawnSpec(entry.id), acpArgs: entry.acpArgs ?? ['acp'] });
}

function validProbeCache(id, entry, now = Date.now()) {
  const cached = ACP_PROBE_CACHE.get(id);
  if (!cached || now - cached.at >= ACP_PROBE_TTL_MS || cached.signature !== probeSignature(entry)) {
    if (cached) ACP_PROBE_CACHE.delete(id);
    return null;
  }
  return cached;
}

export async function probeKernels() {
  const list = listKernels();
  if (process.env.TERMDESK_KERNELS_PROBE !== '1') return list;

  const now = Date.now();
  const out = [];
  for (const entry of list) {
    if (entry.tier !== 'acp' || !entry.available) {
      out.push(entry);
      continue;
    }
    const cached = validProbeCache(entry.id, allEntries().find((item) => item.id === entry.id), now);
    if (cached) {
      out.push({ ...entry, detail: cached.detail, selectable: entry.selectable && cached.ok, ...(cached.acp ? { acp: cached.acp } : {}) });
      continue;
    }
    try {
      const source = allEntries().find((item) => item.id === entry.id);
      const probe = await probeAcpKernel(source);
      const result = { at: Date.now(), signature: probeSignature(source), ok: true, detail: probe.detail, acp: probe.caps };
      ACP_PROBE_CACHE.set(entry.id, result);
      out.push({ ...entry, detail: probe.detail, acp: probe.caps });
    } catch (err) {
      const source = allEntries().find((item) => item.id === entry.id);
      const detail = `ACP 握手失败：${String(err?.message ?? err).slice(0, 80)}`;
      ACP_PROBE_CACHE.set(entry.id, { at: Date.now(), signature: probeSignature(source), ok: false, detail, acp: null });
      out.push({ ...entry, detail, selectable: false });
    }
  }
  return out;
}

/**
 * One kernel, re-read and — for ACP — actually asked to shake hands.
 *
 * This is what the console's "重新检测" does. A plain re-list only re-stats the
 * same path; the interesting question after a path change or an update is
 * whether the binary still speaks the protocol, so an ACP kernel is spawned
 * through the same adapter a conversation would use.
 */
export async function probeKernel(id) {
  const entry = allEntries().find((item) => item.id === id);
  const descriptor = listKernels().find((k) => k.id === id);
  if (!entry || !descriptor) return { ok: false, code: 'unknown_kernel', message: `没有这个内核："${id}"` };
  if (!descriptor.available) return { ok: false, code: 'not_installed', message: descriptor.detail };
  ACP_PROBE_CACHE.delete(id);
  if (entry.tier !== 'acp') {
    return {
      ok: true,
      code: 'detected',
      message: `${descriptor.label}：找到 ${descriptor.path ?? descriptor.detail}`,
      detail: descriptor.detail,
    };
  }
  try {
    const probe = await probeAcpKernel(entry);
    ACP_PROBE_CACHE.set(id, { at: Date.now(), signature: probeSignature(entry), ok: true, detail: probe.detail, acp: probe.caps });
    return {
      ok: true,
      code: 'handshake',
      message: `${descriptor.label} ${probe.detail}`,
      detail: probe.detail,
      acp: probe.caps,
    };
  } catch (error) {
    const detail = `ACP 握手失败：${String(error?.message ?? error).slice(0, 160)}`;
    ACP_PROBE_CACHE.set(id, { at: Date.now(), signature: probeSignature(entry), ok: false, detail, acp: null });
    return {
      ok: false,
      code: 'probe_failed',
      message: `${descriptor.label} ${detail}`,
    };
  }
}

/**
 * One ACP handshake through the same adapter the chat pipeline uses.
 * Capability claims are not taken on faith: a kernel that says `loadSession`
 * and then fails the call is exactly the drift this reports.
 */
async function probeAcpKernel(entry) {
  const spec = spawnSpec(entry.id);
  if (!spec) throw new Error('找不到可执行文件');
  const kernel = new AcpKernel({ id: entry.id, label: entry.label, bin: spec.bin, args: spec.args, log: () => {} });
  try {
    await kernel.ensureStarted();
    const caps = kernel.sessionSupport();
    const name = kernel.agentInfo?.name ?? entry.label;
    const version = kernel.agentInfo?.version ?? '';
    const bits = [];
    if (caps.loadSession) bits.push('可回放历史');
    if (caps.list) bits.push('可列会话');
    if (caps.resume) bits.push('可恢复');
    if (caps.fork) bits.push('可 fork');
    if (caps.close) bits.push('可关闭');
    if (caps.image) bits.push('支持图片');
    return { caps, detail: `ACP \u00b7 ${name} ${version}：${bits.join(' / ') || '仅握手'}` };
  } finally {
    kernel.dispose();
  }
}
