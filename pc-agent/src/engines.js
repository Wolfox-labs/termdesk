/**
 * AI engine layer: drive Codex and DSH from the agent.
 *
 * @deprecated The separate "task" pipeline (`ai.submit` / `ai.tasks` / …) is
 * kept only for wire compatibility. The product path is the unified chat
 * pipeline in `chat.js`: `chat.create` with `engine: 'codex' | 'dsh'`, then
 * `chat.send`. Codex multi-turn still uses the same `codex exec` /
 * `codex exec resume <thread_id>` mechanism implemented here — the chat
 * pipeline reuses `findCodex` / `killProcessTree` and the same JSONL event
 * vocabulary — but new features should land on `chat.*`, not on tasks.
 *
 * Both engines were probed before this was written (see tools/engine-probe*),
 * and they behave differently in ways that shape the whole design:
 *
 *   Codex — `codex exec --json` emits newline-delimited JSON events. It streams
 *     `item.started` when a command begins and `item.completed` when it ends, so
 *     the phone can show live task progress. `codex exec resume <thread_id>`
 *     genuinely preserves conversation context, so multi-turn works.
 *
 *   DSH — `dsh --profile headless "<task>"` is one-shot: it prints the final
 *     answer and exits, and a second invocation has NO memory of the first
 *     (verified: it denied any earlier request). There are no progress events.
 *     To make multi-turn usable at all, prior turns are prepended to the prompt
 *     as context. That is an approximation, not a real session. The chat
 *     pipeline uses the DSH SDK profile instead, which has real sessions.
 *
 * Both are spawned as child processes. Cancellation kills the process tree,
 * because a coding agent typically has tool subprocesses of its own.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';

const MAX_TASKS = 60;
const MAX_EVENTS_PER_TASK = 400;
const TASK_TIMEOUT_MS = 30 * 60 * 1000;

/** Resolve the Codex binary: env override, then the known install location. */
export function findCodex() {
  if (process.env.TERMDESK_CODEX) return process.env.TERMDESK_CODEX;
  const base = path.join(os.homedir(), 'AppData', 'Local', 'OpenAI', 'Codex', 'bin');
  try {
    // bin/<hash>/codex.exe — pick any hash directory that has the binary.
    for (const entry of fs.readdirSync(base)) {
      const candidate = path.join(base, entry, 'codex.exe');
      if (fs.existsSync(candidate)) return candidate;
    }
  } catch {
    // fall through to the PATH name
  }
  return 'codex';
}

/** Resolve the DSH launcher entry script. */
export function findDsh() {
  if (process.env.TERMDESK_DSH) return process.env.TERMDESK_DSH;
  return path.join(
    os.homedir(),
    'AppData',
    'Roaming',
    'io.github.hairyf.deepseek-harness-desktop',
    'dependencies',
    'dsh',
    'node_modules',
    '@deepseek-ai',
    'dsh',
    'lib',
    'bin.js',
  );
}

export const ENGINES = ['codex', 'dsh'];

/**
 * Build the `codex exec` argv for one turn.
 *
 * Shared by the deprecated task pipeline and the unified chat pipeline so
 * multi-turn is one mechanism: a fresh turn runs `exec`, a follow-up runs
 * `exec resume <thread_id>` and Codex itself carries the history — the
 * transcript is never re-fed into a new process.
 *
 * @param {object} options
 * @param {string} options.prompt
 * @param {string} options.cwd
 * @param {string|null} [options.resumeThreadId] thread id from `thread.started`
 * @param {string|null} [options.provider] optional model_provider override
 * @param {string|null} [options.model] optional model override
 */
export function buildCodexExecArgs({ prompt, cwd, resumeThreadId = null, provider = null, model = null }) {
  const useResume = Boolean(resumeThreadId);
  const args = useResume
    ? ['exec', 'resume', resumeThreadId, '--json', '--skip-git-repo-check']
    : ['exec', '--json', '--skip-git-repo-check', '-C', cwd];
  // Codex accepts -c key=value config overrides; provider/model ride along
  // without rewriting config.toml (codexconfig.js still owns persistent setup).
  if (provider) args.push('-c', `model_provider=${provider}`);
  if (model) args.push('-c', `model=${model}`);
  args.push(prompt);
  return args;
}

/**
 * Kill a child and its descendants. A coding agent spawns shells, so killing
 * only the direct child would leave orphans running. Shared with chat.js so
 * both pipelines cancel the same way.
 */
export function killProcessTree(child) {
  if (!child || child.killed) return;
  try {
    if (process.platform === 'win32' && child.pid) {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
        windowsHide: true,
        stdio: 'ignore',
      });
    } else {
      child.kill('SIGKILL');
    }
  } catch {
    try { child.kill(); } catch { /* already gone */ }
  }
}

/** One submitted task and everything observed about it. */
class Task {
  constructor(id, engine, prompt, cwd) {
    this.id = id;
    this.engine = engine;
    this.prompt = prompt;
    this.cwd = cwd;
    this.status = 'running'; // running | completed | failed | cancelled
    this.events = [];
    this.startedAt = Date.now();
    this.finishedAt = null;
    this.exitCode = null;
    /** Codex thread id, retained so follow-ups can resume the same conversation. */
    this.threadId = null;
    this.finalText = '';
    this.child = null;
    this.timer = null;
  }

  push(event) {
    this.events.push({ at: Date.now(), ...event });
    if (this.events.length > MAX_EVENTS_PER_TASK) {
      this.events.splice(0, this.events.length - MAX_EVENTS_PER_TASK);
    }
  }
}

export class EngineManager {
  /**
   * @deprecated Task pipeline; prefer ChatManager (`chat.*`) which is the
   * unified conversation entry. Kept for `ai.*` wire compatibility.
   */
  constructor() {
    this.tasks = new Map();
    this.nextId = 1;
    /** Conversation continuity: engine -> thread/session handle. */
    this.sessions = { codex: null, dsh: [] };
    this.onEvent = null;
  }

  attach(onEvent) {
    this.onEvent = onEvent;
  }

  detach() {
    this.onEvent = null;
  }

  emit(payload) {
    this.onEvent?.(payload);
  }

  listTasks() {
    return [...this.tasks.values()]
      .map((t) => ({
        id: t.id,
        engine: t.engine,
        prompt: t.prompt.slice(0, 200),
        status: t.status,
        startedAt: t.startedAt,
        finishedAt: t.finishedAt,
        exitCode: t.exitCode,
        threadId: t.threadId,
        eventCount: t.events.length,
      }))
      .sort((a, b) => b.startedAt - a.startedAt);
  }

  getTask(id) {
    const t = this.tasks.get(id);
    if (!t) return null;
    return {
      id: t.id,
      engine: t.engine,
      prompt: t.prompt,
      cwd: t.cwd,
      status: t.status,
      startedAt: t.startedAt,
      finishedAt: t.finishedAt,
      exitCode: t.exitCode,
      threadId: t.threadId,
      finalText: t.finalText,
      events: t.events,
    };
  }

  /** Stop a running task and everything it spawned. */
  cancel(id) {
    const t = this.tasks.get(id);
    if (!t || t.status !== 'running') return false;
    t.status = 'cancelled';
    t.finishedAt = Date.now();
    this.killTree(t);
    t.push({ kind: 'cancelled', text: '任务已取消' });
    this.emit({ event: 'task.finished', taskId: t.id, status: 'cancelled' });
    return true;
  }

  /**
   * Kill the child and its descendants.
   * @deprecated see killProcessTree; kept as a method for the task pipeline.
   */
  killTree(task) {
    killProcessTree(task.child);
  }

  /** Forget stored conversations so the next task starts clean. */
  resetSession(engine) {
    if (engine === 'codex') this.sessions.codex = null;
    else if (engine === 'dsh') this.sessions.dsh = [];
    else return false;
    return true;
  }

  submit({ engine, prompt, cwd, resume }) {
    if (!ENGINES.includes(engine)) {
      return { ok: false, code: 'bad_engine', message: `不支持的引擎 "${engine}"` };
    }
    const text = String(prompt ?? '').trim();
    if (text.length === 0) {
      return { ok: false, code: 'empty_prompt', message: '任务内容不能为空' };
    }
    if (this.tasks.size >= MAX_TASKS) {
      // Drop the oldest finished task to make room.
      const finished = [...this.tasks.values()]
        .filter((t) => t.status !== 'running')
        .sort((a, b) => a.startedAt - b.startedAt);
      if (finished.length > 0) this.tasks.delete(finished[0].id);
    }

    const id = `t${this.nextId++}`;
    const workdir = cwd && fs.existsSync(cwd) ? cwd : os.homedir();
    const task = new Task(id, engine, text, workdir);
    this.tasks.set(id, task);
    this.emit({ event: 'task.started', taskId: id, engine, prompt: text });

    if (engine === 'codex') {
      this.runCodex(task, { resume: Boolean(resume) });
    } else {
      this.runDsh(task);
    }
    return { ok: true, taskId: id };
  }

  // --- Codex -------------------------------------------------------------

  runCodex(task, { resume }) {
    const codex = findCodex();
    const args = buildCodexExecArgs({
      prompt: task.prompt,
      cwd: task.cwd,
      resumeThreadId: resume ? this.sessions.codex : null,
    });

    const child = spawn(codex, args, {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      cwd: task.cwd,
    });
    task.child = child;

    task.timer = setTimeout(() => {
      if (task.status === 'running') {
        task.push({ kind: 'error', text: '任务超时（30 分钟）' });
        task.status = 'failed';
        task.finishedAt = Date.now();
        this.killTree(task);
        this.emit({ event: 'task.finished', taskId: task.id, status: 'failed' });
      }
    }, TASK_TIMEOUT_MS);

    let buffer = '';
    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      let nl;
      while ((nl = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (line.length === 0) continue;
        this.handleCodexEvent(task, line);
      }
    });

    let stderr = '';
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
      if (stderr.length > 8000) stderr = stderr.slice(-8000);
    });

    child.on('error', (err) => {
      clearTimeout(task.timer);
      task.status = 'failed';
      task.finishedAt = Date.now();
      task.push({ kind: 'error', text: `无法启动 Codex：${err.message}` });
      this.emit({ event: 'task.finished', taskId: task.id, status: 'failed' });
    });

    child.on('close', (code) => {
      clearTimeout(task.timer);
      if (task.status === 'cancelled') return;
      task.exitCode = code;
      task.status = code === 0 ? 'completed' : 'failed';
      task.finishedAt = Date.now();
      if (code !== 0 && stderr.trim().length > 0) {
        task.push({ kind: 'error', text: stderr.trim().split('\n').slice(-3).join('\n') });
      }
      this.emit({
        event: 'task.finished',
        taskId: task.id,
        status: task.status,
        exitCode: code,
      });
    });
  }

  /** Translate one Codex JSONL event into a task event. */
  handleCodexEvent(task, line) {
    let ev;
    try {
      ev = JSON.parse(line);
    } catch {
      return; // Codex occasionally writes non-JSON noise; ignore it.
    }

    if (ev.type === 'thread.started') {
      task.threadId = ev.thread_id;
      this.sessions.codex = ev.thread_id;
      task.push({ kind: 'system', text: `会话 ${ev.thread_id.slice(0, 8)}` });
    } else if (ev.type === 'turn.started') {
      task.push({ kind: 'turn', text: '开始处理', state: 'started' });
    } else if (ev.type === 'turn.completed') {
      const usage = ev.usage ?? {};
      task.push({
        kind: 'turn',
        text: '处理完成',
        state: 'completed',
        tokens: usage.output_tokens ?? null,
      });
    } else if (ev.type === 'item.started' || ev.type === 'item.completed') {
      const item = ev.item ?? {};
      const done = ev.type === 'item.completed';

      if (item.type === 'command_execution') {
        task.push({
          kind: 'command',
          text: item.command ?? '',
          state: done ? 'completed' : 'running',
          exitCode: done ? item.exit_code ?? null : null,
          output: done ? (item.aggregated_output ?? '').slice(0, 4000) : '',
        });
      } else if (item.type === 'agent_message' && done) {
        task.finalText = item.text ?? '';
        task.push({ kind: 'message', text: task.finalText });
      } else if (item.type === 'reasoning' && done) {
        task.push({ kind: 'reasoning', text: (item.text ?? '').slice(0, 2000) });
      } else if (item.type === 'error' && done) {
        // Codex reports config warnings as errors; keep them but mark them low
        // severity so they do not look like task failures.
        task.push({ kind: 'warning', text: item.message ?? '' });
      } else if (item.type === 'file_change' && done) {
        const changes = item.changes ?? [];
        task.push({
          kind: 'files',
          text: changes.map((c) => `${c.kind ?? 'change'}: ${c.path ?? ''}`).join('\n'),
        });
      }
    }

    this.emit({ event: 'task.event', taskId: task.id, engine: task.engine });
  }

  // --- DSH ---------------------------------------------------------------

  runDsh(task) {
    const bin = findDsh();
    if (!fs.existsSync(bin)) {
      task.status = 'failed';
      task.finishedAt = Date.now();
      task.push({ kind: 'error', text: `找不到 DSH 入口：${bin}` });
      this.emit({ event: 'task.finished', taskId: task.id, status: 'failed' });
      return;
    }

    // DSH headless has no session continuity, so prior turns are folded into
    // the prompt. This is an approximation and is labelled as such in the UI.
    const history = this.sessions.dsh;
    let prompt = task.prompt;
    if (history.length > 0) {
      const transcript = history
        .slice(-6)
        .map((h) => `用户：${h.prompt}\n助手：${h.answer}`)
        .join('\n\n');
      prompt = `以下是此前的对话记录，请据此理解上下文：\n\n${transcript}\n\n当前任务：${task.prompt}`;
      task.push({ kind: 'system', text: `已附带 ${Math.min(history.length, 6)} 轮上下文（DSH 无原生会话续接）` });
    }

    const child = spawn(process.execPath, [bin, '--profile', 'headless', prompt], {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      cwd: task.cwd,
    });
    task.child = child;

    task.timer = setTimeout(() => {
      if (task.status === 'running') {
        task.push({ kind: 'error', text: '任务超时（30 分钟）' });
        task.status = 'failed';
        task.finishedAt = Date.now();
        this.killTree(task);
        this.emit({ event: 'task.finished', taskId: task.id, status: 'failed' });
      }
    }, TASK_TIMEOUT_MS);

    // DSH streams reasoning deltas to stderr; surface them as progress.
    let stderrBuffer = '';
    child.stderr.on('data', (chunk) => {
      stderrBuffer += chunk.toString('utf8');
      let nl;
      while ((nl = stderrBuffer.indexOf('\n')) !== -1) {
        const line = stderrBuffer.slice(0, nl).trim();
        stderrBuffer = stderrBuffer.slice(nl + 1);
        if (line.length === 0) continue;
        if (line.startsWith('[ctrl-immune]')) continue; // launcher noise
        task.push({ kind: 'reasoning', text: line.slice(0, 2000) });
        this.emit({ event: 'task.event', taskId: task.id, engine: task.engine });
      }
    });

    let stdout = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });

    child.on('error', (err) => {
      clearTimeout(task.timer);
      task.status = 'failed';
      task.finishedAt = Date.now();
      task.push({ kind: 'error', text: `无法启动 DSH：${err.message}` });
      this.emit({ event: 'task.finished', taskId: task.id, status: 'failed' });
    });

    child.on('close', (code) => {
      clearTimeout(task.timer);
      if (task.status === 'cancelled') return;
      task.exitCode = code;

      // The launcher writes a banner line to stdout before the answer.
      const answer = stdout
        .split(/\r?\n/)
        .filter((l) => !l.startsWith('[ctrl-immune]'))
        .join('\n')
        .trim();

      task.finalText = answer;
      if (answer.length > 0) task.push({ kind: 'message', text: answer });

      task.status = code === 0 && answer.length > 0 ? 'completed' : 'failed';
      task.finishedAt = Date.now();

      if (task.status === 'completed') {
        this.sessions.dsh.push({ prompt: task.prompt, answer });
        if (this.sessions.dsh.length > 12) this.sessions.dsh.shift();
      } else if (answer.length === 0) {
        task.push({ kind: 'error', text: `DSH 未返回内容（退出码 ${code}）` });
      }

      this.emit({
        event: 'task.finished',
        taskId: task.id,
        status: task.status,
        exitCode: code,
      });
    });
  }

  /**
   * Kernel discovery: what this PC can actually talk to.
   *
   * Answers three questions the picker needs — is it installed, how is it
   * reached, and what can it do — and is honest about the tier:
   *
   *   native  an adapter is wired into the chat pipeline (codex, dsh)
   *   acp     the kernel serves ACP (session list/resume/prompt); adapter pending
   *   shim    CLI-shaped kernel; needs a manifest shim
   *
   * `detail` is user-facing: it says *why* an entry is not usable, so the picker
   * never offers something the phone cannot actually open.
   *
   * Discovery is deliberately cheap (PATH + known install paths, no process
   * spawn). `TERMDESK_KERNELS_PROBE=1` additionally runs the ACP handshakes and
   * reports the declared capabilities.
   */
  async probeEngines() {
    const codex = findCodex();
    const dsh = findDsh();
    const list = [
      {
        id: 'codex',
        label: 'Codex',
        tier: 'native',
        transport: 'app-server',
        available: fs.existsSync(codex) || codex === 'codex',
        path: codex,
        detail: '官方 app-server：列表/读取/恢复/fork/打断全部由内核提供',
        multiTurn: true,
        progress: true,
        resume: true,
      },
      {
        id: 'dsh',
        label: 'DeepSeek Harness',
        tier: 'native',
        transport: 'sdk',
        available: fs.existsSync(dsh),
        path: dsh,
        // The chat path uses the sdk profile, which has no session resume; the
        // acp profile can list/resume but has no transcript replay.
        detail: '当前走 sdk 协议（原生延续，不支持恢复历史会话）',
        multiTurn: true,
        progress: true,
        resume: false,
      },
    ];

    // Kernels that expose ACP but are not wired into the chat pipeline yet.
    const acpHints = [
      { id: 'opencode', label: 'OpenCode', candidates: [process.env.TERMDESK_OPENCODE, 'D:\\OpenCode\\opencode-cli.exe', 'opencode'] },
      { id: 'mimo', label: 'MiMo Code', candidates: [process.env.TERMDESK_MIMO, path.join(os.homedir(), 'AppData', 'Roaming', 'npm', 'mimo.cmd'), 'mimo'] },
      { id: 'qoder', label: 'QoderWork', tier: 'shim', candidates: [path.join(os.homedir(), 'AppData', 'Local', 'Programs', 'QoderWork CN', 'resources', 'bin', 'qoderclicn.exe')] },
      { id: 'command-code', label: 'Command Code', tier: 'shim', candidates: [path.join(os.homedir(), 'AppData', 'Roaming', 'npm', 'command-code.ps1'), 'command-code'] },
    ];
    for (const hint of acpHints) {
      const found = hint.candidates.find((c) => c && fs.existsSync(c)) ?? null;
      const tier = hint.tier ?? 'acp';
      list.push({
        id: hint.id,
        label: hint.label,
        tier,
        transport: tier === 'acp' ? 'acp' : 'cli',
        available: Boolean(found),
        path: found,
        detail: !found
          ? '未在本机找到'
          : tier === 'acp'
            ? '已发现 ACP 服务端；TermDesk 适配器尚未接入（选择后暂不可用）'
            : 'CLI 形状，需要 shim 适配（选择后暂不可用）',
        multiTurn: tier === 'acp',
        progress: tier === 'acp',
        resume: tier === 'acp',
      });
    }

    if (process.env.TERMDESK_KERNELS_PROBE === '1') {
      for (const entry of list) {
        if (!entry.available || entry.tier !== 'acp') continue;
        entry.detail = await this.probeAcpKernel(entry).catch((err) => `ACP 握手失败：${String(err?.message ?? err).slice(0, 80)}`);
      }
    }
    return list;
  }

  /** One ACP `initialize` handshake, reported as a human-readable capability line. */
  async probeAcpKernel(entry) {
    const { probeAcpServer } = await import('./kernels/acp-probe.js');
    const caps = await probeAcpServer(entry);
    entry.acp = caps;
    const bits = [];
    if (caps.loadSession) bits.push('可回放历史');
    if (caps.sessionList) bits.push('可列会话');
    if (caps.sessionResume) bits.push('可恢复');
    if (caps.fork) bits.push('可fork');
    if (caps.promptOk) bits.push('可发消息');
    for (const key of ['loadSession', 'sessionList', 'sessionResume', 'fork', 'promptOk']) delete caps[key];
    return `ACP：${bits.join('/') || '仅握手'}`;
  }

  disposeAll() {
    for (const task of this.tasks.values()) {
      if (task.status === 'running') {
        clearTimeout(task.timer);
        this.killTree(task);
        task.status = 'cancelled';
      }
    }
  }
}
