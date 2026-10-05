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

const NPM_ROOT = path.join(os.homedir(), 'AppData', 'Roaming', 'npm');
const LOCAL_PROGRAMS = path.join(os.homedir(), 'AppData', 'Local', 'Programs');

/** Absolute path if it exists and is a file, else null. */
function fileOrNull(candidate) {
  if (!candidate) return null;
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

/**
 * Node-hosted CLIs (npm global installs) are spawned as
 * `node <entry> <args…>`. `process.execPath` is used rather than a bare `node`
 * so a stripped PATH (scheduled task, service) cannot break the launch.
 */
function nodeEntry(candidates) {
  const entry = firstFile(candidates);
  return entry ? { bin: process.execPath, args: [entry] } : null;
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
      detail: '官方 app-server：列表 / 读取 / 恢复 / fork / 打断都由内核提供',
      resolve: () => {
        if (process.env.TERMDESK_CODEX) return fileOrNull(process.env.TERMDESK_CODEX) ?? process.env.TERMDESK_CODEX;
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
      id: 'dsh',
      label: 'DeepSeek Harness',
      tier: 'native',
      transport: 'sdk',
      resume: false,
      detail: 'SDK 运行时：可连续对话；历史恢复尚未开放',
      resolve: () => {
        if (process.env.TERMDESK_DSH) return process.env.TERMDESK_DSH;
        // Must be the JS entry: the chat pipeline launches it as
        // `node <entry> --profile sdk`, and `bin/dsh.ps1` is only a launcher shim.
        return firstFile([
          path.join(os.homedir(), 'AppData', 'Roaming', 'io.github.hairyf.deepseek-harness-desktop',
            'dependencies', 'dsh', 'node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js'),
        ]);
      },
    },
    {
      id: 'opencode',
      label: 'OpenCode',
      tier: 'acp',
      transport: 'acp',
      acpArgs: ['acp'],
      resume: true,
      detail: 'ACP：会话列表 / 恢复 / fork / 图片与内嵌上下文',
      resolve: () => {
        if (process.env.TERMDESK_OPENCODE) return fileOrNull(process.env.TERMDESK_OPENCODE) ?? process.env.TERMDESK_OPENCODE;
        return firstFile([
          path.join('D:', path.sep, 'OpenCode', 'opencode-cli.exe'),
          path.join(LOCAL_PROGRAMS, '@opencode-aidesktop', 'resources', 'bin', 'opencode-cli.exe'),
          path.join(NPM_ROOT, 'opencode.cmd'),
        ]);
      },
    },
    {
      id: 'mimo',
      label: 'MiMo Code',
      tier: 'acp',
      transport: 'acp',
      acpArgs: ['acp'],
      resume: true,
      detail: 'ACP（OpenCode 内核）：会话列表 / 恢复 / fork',
      resolve: () => {
        if (process.env.TERMDESK_MIMO) return fileOrNull(process.env.TERMDESK_MIMO) ?? process.env.TERMDESK_MIMO;
        return nodeEntry([
          path.join(NPM_ROOT, 'node_modules', '@mimo-ai', 'cli', 'bin', 'mimo'),
        ]);
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
      resolve: () => fileOrNull(process.env.TERMDESK_QODER)
        ?? firstFile([path.join(LOCAL_PROGRAMS, 'QoderWork CN', 'resources', 'bin', 'qoderclicn.exe')]),
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
      acpArgs: ['acp'],
      resume: true,
      detail: 'ACP（1.74.1 原生）：会话列表 / 读取回放 / 恢复 / 图片与内嵌上下文',
      resolve: () => {
        if (process.env.TERMDESK_COMMAND_CODE) return fileOrNull(process.env.TERMDESK_COMMAND_CODE) ?? process.env.TERMDESK_COMMAND_CODE;
        return nodeEntry([
          path.join(NPM_ROOT, 'node_modules', 'command-code', 'dist', 'index.mjs'),
        ]);
      },
    },
    {
      id: 'antigravity',
      label: 'Antigravity',
      tier: 'unsupported',
      transport: null,
      detail: '只有图形界面与语言服务进程，没有可编程的会话接口',
      resolve: () => fileOrNull(path.join(LOCAL_PROGRAMS, 'antigravity', 'Antigravity.exe')),
    },
    {
      id: 'doubao',
      label: '豆包',
      tier: 'unsupported',
      transport: null,
      detail: '桌面聊天应用，未提供命令行或协议接口',
      resolve: () => fileOrNull(path.join(os.homedir(), 'AppData', 'Local', 'Doubao', 'Application', 'Doubao.exe')),
    },
  ];
}

/** Extra ACP kernels from the environment, so a new one needs no code change. */
function envKernels() {
  const raw = process.env.TERMDESK_ACP_KERNELS;
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
      acpArgs: Array.isArray(k.args) ? k.args : ['acp'],
      // ACP by construction, and ACP declares session capabilities, so the
      // default is "it can reopen a past conversation" unless the entry says
      // otherwise. (`resume: Boolean(entry.resume)` would have silently disabled
      // it for every kernel added through the environment.)
      resume: k.resume !== false,
      detail: '来自 TERMDESK_ACP_KERNELS 的 ACP 内核',
      // `preArgs` is the prelude a node-hosted CLI needs (node + script);
      // without it the flags would go to the runtime instead of the CLI.
      resolve: () => (Array.isArray(k.preArgs) && k.preArgs.length
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
function envCliKernels() {
  const raw = process.env.TERMDESK_CLI_KERNELS;
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
      resolve: () => (Array.isArray(k.preArgs) && k.preArgs.length
        ? { bin: fileOrNull(k.bin) ?? k.bin, args: k.preArgs }
        : (fileOrNull(k.bin) ?? k.bin)),
    }));
}

function allEntries() {
  return [...table(), ...envKernels(), ...envCliKernels()];
}

/**
 * Resolved command for an entry: `{ bin, args, script }` or null when not
 * installed. `script` is the user-meaningful file when the real executable is
 * only a runtime host (node + an npm entry), so the picker shows the kernel
 * rather than "node.exe".
 */
export function kernelCommand(entry) {
  const resolved = entry.resolve();
  if (!resolved) return null;
  if (typeof resolved === 'string') return { bin: resolved, args: [], script: null };
  return { bin: resolved.bin, args: [...(resolved.args ?? [])], script: resolved.args?.[0] ?? null };
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

export function listKernels() {
  return allEntries().map((entry) => {
    const command = kernelCommand(entry);
    const available = Boolean(command);
    return {
      id: entry.id,
      label: entry.label,
      tier: entry.tier,
      transport: entry.transport,
      available,
      // A shim is selectable only once its manifest has been confirmed by a
      // real run: an unverified manifest is a guess about argv and about which
      // JSON key carries the answer, and offering it would be offering a kernel
      // that may well answer nothing.
      selectable: available && (
        entry.tier === 'native' || entry.tier === 'acp' || (entry.tier === 'shim' && shimVerified(entry))
      ),
      path: command ? (command.script ?? command.bin) : null,
      args: command?.args ?? null,
      detail: available ? entry.detail : (entry.tier === 'unsupported' ? '未安装' : '未在本机找到'),
      shim: entry.shim ?? null,
      multiTurn: entry.tier === 'native' || entry.tier === 'acp' || (entry.tier === 'shim' && shimVerified(entry)),
      progress: entry.tier === 'native' || entry.tier === 'acp' || entry.tier === 'shim',
      // Per kernel, NOT per tier: "native" describes how it is driven, not
      // whether it can reopen a past conversation. DSH is native and still has
      // no verified resume, and saying otherwise is what makes a phone offer a
      // button that cannot work.
      resume: Boolean(entry.resume),
    };
  });
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

/** `{ bin, args }` for one kernel id, with cwd applied by the caller. */
export function spawnSpec(id) {
  const entry = allEntries().find((k) => k.id === id);
  if (!entry) return null;
  const command = kernelCommand(entry);
  if (!command) return null;
  if (entry.tier === 'acp') return { bin: command.bin, args: [...command.args, ...(entry.acpArgs ?? ['acp'])] };
  return { bin: command.bin, args: command.args };
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
    const cached = ACP_PROBE_CACHE.get(entry.id);
    if (cached && now - cached.at < ACP_PROBE_TTL_MS) {
      out.push({ ...entry, detail: cached.detail, acp: cached.acp });
      continue;
    }
    try {
      const probe = await probeAcpKernel(entry);
      ACP_PROBE_CACHE.set(entry.id, { at: now, detail: probe.detail, acp: probe.caps });
      out.push({ ...entry, detail: probe.detail, acp: probe.caps });
    } catch (err) {
      out.push({ ...entry, detail: `ACP 握手失败：${String(err?.message ?? err).slice(0, 80)}` });
    }
  }
  return out;
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
