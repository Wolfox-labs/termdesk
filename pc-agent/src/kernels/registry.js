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
      transport: 'cli',
      detail: 'CLI 具备会话清单（--list-sessions）与按 id 恢复（--resume），shim 规格已写好，尚缺一次实测',
      shim: {
        // Recorded from `qoderclicn --help` (v0.15.x). `--print` is the
        // non-interactive form; `--output-format` selects the NDJSON stream.
        newArgs: ['--print', '--output-format', 'stream-json'],
        resumeArgs: ['--print', '--output-format', 'stream-json', '--resume'],
        listArgs: ['--list-sessions'],
        sessionId: 'session_id',
      },
      resolve: () => fileOrNull(process.env.TERMDESK_QODER)
        ?? firstFile([path.join(LOCAL_PROGRAMS, 'QoderWork CN', 'resources', 'bin', 'qoderclicn.exe')]),
    },
    {
      id: 'command-code',
      label: 'Command Code',
      tier: 'shim',
      transport: 'cli',
      detail: 'CLI 具备 --resume / --session / --output-format json，shim 规格已写好，尚缺一次实测',
      shim: {
        // Recorded from `command-code --help` (v1.65.0).
        newArgs: ['--print', '--output-format', 'json'],
        resumeArgs: ['--print', '--output-format', 'json', '--session'],
        sessionId: 'session_id',
      },
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
      resolve: () => (fileOrNull(k.bin) ?? k.bin),
    }));
}

function allEntries() {
  return [...table(), ...envKernels()];
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
      selectable: available && (entry.tier === 'native' || entry.tier === 'acp'),
      path: command ? (command.script ?? command.bin) : null,
      args: command?.args ?? null,
      detail: available ? entry.detail : (entry.tier === 'unsupported' ? '未安装' : '未在本机找到'),
      shim: entry.shim ?? null,
      multiTurn: entry.tier === 'native' || entry.tier === 'acp',
      progress: entry.tier === 'native' || entry.tier === 'acp',
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
