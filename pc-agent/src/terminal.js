/**
 * Persistent terminal session.
 *
 * Windows has no usable PTY from Node without native modules, so this drives a
 * long-lived PowerShell process over pipes with a JSON-lines protocol. That
 * design was chosen after measuring alternatives (see tools/probes/pty-probe*.js);
 * the constraints it satisfies are:
 *
 *   - state persists between commands (variables, functions, cwd, env);
 *   - output streams while a command runs, rather than at the end;
 *   - errors are visible — PowerShell's non-terminating errors go to the error
 *     stream, and a terminal that hides them is worse than useless;
 *   - command completion is detectable, via a per-command sentinel.
 *
 * The command body is dot-sourced (`. { ... }`) rather than invoked with the
 * call operator (`& { ... }`). Measured difference: `&` runs in a child scope,
 * so `$x = 5` and `function f {}` silently failed to persist (2/5 on a scope
 * test), while `.` persists all of them (5/5).
 *
 * Commands travel base64-encoded inside JSON so quotes, pipes, newlines and
 * non-ASCII survive the pipe intact.
 */
import { spawn } from 'node:child_process';
import os from 'node:os';

const IDLE_TIMEOUT_MS = 30 * 60 * 1000; // reap a session after 30 min idle
const MAX_SCROLLBACK = 2000; // retained output lines per session
const MAX_COMMAND_BYTES = 100_000;

/**
 * A base64 field from a sentinel, or null when the shell did not send one.
 *
 * Both loops now carry the shell's cwd this way. A shell that emits only two
 * fields - an older build, or a platform we have not taught yet - yields null,
 * and the phone shows no directory rather than a wrong one.
 */
function decodeBase64(value) {
  if (!value) return null;
  try {
    return Buffer.from(value, 'base64').toString('utf8');
  } catch {
    return null;
  }
}

const LOOP_SCRIPT = `
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  try {
    $req = $line | ConvertFrom-Json
    $cmd = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($req.c))
    $global:LASTEXITCODE = 0
    . { Invoke-Expression $cmd } 2>&1 | ForEach-Object {
      if ($_ -is [System.Management.Automation.ErrorRecord]) {
        [Console]::Out.WriteLine("ERR: " + $_.Exception.Message)
      } elseif ($_ -is [System.Management.Automation.WarningRecord]) {
        [Console]::Out.WriteLine("WARN: " + $_.Message)
      } elseif ($_ -is [System.Management.Automation.VerboseRecord]) {
        [Console]::Out.WriteLine("VERBOSE: " + $_.Message)
      } else {
        [Console]::Out.WriteLine([string]$_)
      }
    }
    $code = $global:LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    [Console]::Out.WriteLine("ERR: " + $_.Exception.Message)
    $code = 1
  }
  $cwdB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($PWD.Path))
  [Console]::Out.WriteLine("__TD_END_$($req.id)_" + $code + "_" + $cwdB64 + "__")
  [Console]::Out.Flush()
}
`;

/**
 * The same contract, for a POSIX shell.
 *
 * Why not the JSON frame: parsing JSON in shell is a liability (sed/awk quoting
 * bugs, one per platform). The command is base64 on both sides already; here the
 * id and the payload are separated by a TAB, which base64 can never contain, so
 * the loop is three lines and there is nothing to mis-quote. The sentinel, the
 * scrollback and the completion detection stay exactly as they are - the parser
 * never knew which shell produced the bytes.
 */
const POSIX_LOOP_SCRIPT = `
while IFS='	' read -r __td_id __td_payload; do
  [ -z "\$__td_id" ] && continue
  __td_cmd=\$(printf '%s' "\$__td_payload" | base64 -d 2>/dev/null)
  eval "\$__td_cmd"
  __td_code=\$?
  # $PWD rides along base64-encoded: a directory name may contain anything, and
  # the sentinel is split on '_'. base64's alphabet has no '_', so the frame
  # stays parseable whatever the directory is called.
  __td_cwd=\$(printf '%s' "\$PWD" | base64 | tr -d '\\n')
  printf '\\n__TD_END_%s_%s_%s__\\n' "\$__td_id" "\$__td_code" "\$__td_cwd"
done
`;

/** One live PowerShell process plus its scrollback. */
class TerminalSession {
  constructor(id, onOutput) {
    this.id = id;
    this.onOutput = onOutput;
    this.child = null;
    this.buffer = '';
    this.scrollback = [];
    this.listeners = [];
    this.nextId = 1;
    this.lastActivity = Date.now();
    this.closed = false;
    this.currentCommand = null;
    // Incremented on every (re)start. Handlers from a previous child compare
    // against this so a superseded process cannot mark the live session dead.
    this.generation = 0;
  }

  start() {
    this.generation += 1;
    const generation = this.generation;
    this.closed = false;
    this.posix = false;
    this.shellName = 'shell';
    this.buffer = '';
    // The shell belongs to the machine the agent runs on. Windows gets
    // PowerShell (there is no usable PTY there without native modules); anywhere
    // else gets bash, which is what the sandbox has.
    // TERMDESK_POSIX_SHELL=1 exercises the bash path on a Windows host that has
    // one (Git bash). Without it this branch could only ever be tested on the
    // phone, which is exactly the kind of path that rots unnoticed.
    const posix = process.platform !== 'win32' || process.env.TERMDESK_POSIX_SHELL === '1';
    this.shellName = posix ? 'bash' : 'PowerShell';
    this.posix = posix;
    const shellBin = posix ? (process.env.TERMDESK_SHELL || '/bin/bash') : 'powershell.exe';
    // `-c <script>`, not `-s`: with -s bash reads the *program* from stdin, so
    // the loop would never run and the first command would wait forever.
    const shellArgs = posix
      ? ['--norc', '--noprofile', '-c', POSIX_LOOP_SCRIPT]
      : ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', LOOP_SCRIPT];
    // Windows keeps the behaviour it had (the agent starts in its own directory).
    // A POSIX host has a meaningful home - the sandbox - and the caller may name a
    // start directory explicitly, which is what the phone does.
    const cwd = posix
      ? (process.env.TERMDESK_SHELL_CWD || process.env.HOME || os.homedir())
      : undefined;
    this.child = spawn(shellBin, shellArgs, {
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
      cwd,
    });

    this.child.stdout.on('data', (chunk) => {
      if (generation !== this.generation) return; // stale process
      this.handleData(chunk.toString('utf8'));
    });
    this.child.stderr.on('data', (chunk) => {
      if (generation !== this.generation) return;
      const text = chunk.toString('utf8');
      if (text.trim().length > 0) this.emit(text, 'stderr');
    });
    this.child.on('exit', (code) => {
      // A child from a previous generation exiting during a restart must not
      // touch the new session's state.
      if (generation !== this.generation) return;
      this.closed = true;
      this.emit(`\n[会话已结束，退出码 ${code}]\n`, 'system');
      const pending = this.listeners.splice(0);
      for (const waiter of pending) {
        waiter({ output: '', code: code ?? -1, id: -1, ended: true });
      }
    });
    return this;
  }

  /** Accumulate output, emit live chunks, and resolve commands on sentinels. */
  handleData(text) {
    this.buffer += text;
    this.lastActivity = Date.now();

    // Emit everything before the first (possibly incomplete) sentinel as live
    // output, so the user sees progress while a command runs.
    let idx;
    while ((idx = this.buffer.indexOf('__TD_END_')) !== -1) {
      const end = this.buffer.indexOf('__', idx + 9);
      if (end === -1) break;
      const [id, code, cwdB64] = this.buffer.slice(idx + 9, end).split('_');
      const output = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(end + 2);

      if (output.length > 0) this.emit(output, 'stdout');

      const waiter = this.listeners.shift();
      const result = {
        output: output.replace(/\r?\n$/, ''),
        code: Number(code),
        id: Number(id),
        cwd: decodeBase64(cwdB64),
      };
      this.currentCommand = null;
      if (waiter) waiter(result);
    }

    // Flush any trailing partial chunk that cannot contain a full sentinel yet.
    const pendingSentinel = this.buffer.indexOf('__TD_END_');
    if (pendingSentinel === -1 && this.buffer.length > 0) {
      this.emit(this.buffer, 'stdout');
      this.buffer = '';
    } else if (pendingSentinel > 0) {
      this.emit(this.buffer.slice(0, pendingSentinel), 'stdout');
      this.buffer = this.buffer.slice(pendingSentinel);
    }
  }

  emit(text, stream) {
    if (!text) return;
    for (const line of text.split(/\r?\n/)) {
      if (line.length === 0) continue;
      this.scrollback.push({ line, stream, at: Date.now() });
    }
    if (this.scrollback.length > MAX_SCROLLBACK) {
      this.scrollback.splice(0, this.scrollback.length - MAX_SCROLLBACK);
    }
    this.onOutput(this.id, text, stream);
  }

  /** Send a command and resolve when its sentinel arrives. */
  run(command) {
    if (this.closed) {
      return Promise.resolve({ output: '', code: -1, error: 'session_ended' });
    }
    const cmd = String(command ?? '').trim();
    if (cmd.length === 0) {
      return Promise.resolve({ output: '', code: 0 });
    }
    if (Buffer.byteLength(cmd, 'utf8') > MAX_COMMAND_BYTES) {
      return Promise.resolve({ output: '', code: -1, error: 'command_too_long' });
    }

    return new Promise((resolve) => {
      const id = this.nextId++;
      this.listeners.push(resolve);
      this.currentCommand = { id, command: cmd, startedAt: Date.now() };
      this.lastActivity = Date.now();
      const payload = Buffer.from(cmd, 'utf8').toString('base64');
      // Two frames, one contract: bash reads '<id>\t<base64>', PowerShell reads JSON.
      const frame = this.posix ? `${id}\t${payload}\n` : `${JSON.stringify({ id, c: payload })}\n`;
      try {
        this.child.stdin.write(frame);
      } catch {
        this.listeners.pop();
        resolve({ output: '', code: -1, error: 'write_failed' });
      }
    });
  }

  /** Send Ctrl+C: stop the running command without killing the session. */
  interrupt() {
    if (this.closed || !this.currentCommand) return false;
    // Abandon the in-flight command and rebuild the shell. PowerShell has no
    // clean way to break into a blocked pipeline from here, and recreating the
    // process is more predictable than injecting a break. Pending waiters are
    // resolved first so a caller awaiting a command is never left hanging.
    const pending = this.listeners.splice(0);
    for (const waiter of pending) {
      waiter({ output: '', code: -2, error: 'interrupted' });
    }
    this.emit('^C\n', 'system');
    this.restart();
    return true;
  }

  restart() {
    this.buffer = '';
    this.currentCommand = null;
    const old = this.child;
    // start() bumps the generation, so the old child's exit handler becomes a
    // no-op instead of marking the fresh session as ended.
    this.start();
    try { old?.kill(); } catch { /* already gone */ }
    this.emit('[已重启 shell]\n', 'system');
  }

  snapshot() {
    return {
      id: this.id,
      scrollback: this.scrollback.map((e) => ({ line: e.line, stream: e.stream })),
      running: this.currentCommand !== null,
    };
  }

  dispose() {
    this.closed = true;
    this.listeners.length = 0;
    try { this.child?.kill(); } catch { /* already gone */ }
  }

  isIdle(now = Date.now()) {
    return now - this.lastActivity > IDLE_TIMEOUT_MS;
  }
}

/**
 * Manages terminal sessions. Sessions are keyed by id so a client can keep
 * several shells and reconnect to the scrollback after a network drop.
 */
export class TerminalManager {
  constructor() {
    this.sessions = new Map();
    this.owner = null; // socket that currently receives output
    this.nextId = 1;
    this.reaper = setInterval(() => this.reapIdle(), 60_000);
    this.reaper.unref?.();
  }

  /** Route output to the socket that owns the terminal right now. */
  attach(onOutput) {
    this.owner = onOutput;
  }

  detach() {
    this.owner = null;
  }

  create() {
    const id = `sh${this.nextId++}`;
    const session = new TerminalSession(id, (sid, text, stream) => {
      this.owner?.(sid, text, stream);
    }).start();
    this.sessions.set(id, session);
    session.emit(`TermDesk shell ${id} — ${session.shellName ?? 'shell'}\n`, 'system');
    return session;
  }

  get(id) {
    return this.sessions.get(id) ?? null;
  }

  list() {
    return [...this.sessions.values()].map((s) => ({
      id: s.id,
      running: s.currentCommand !== null,
      lastActivity: s.lastActivity,
      pid: s.child?.pid ?? null,
    }));
  }

  close(id) {
    const session = this.sessions.get(id);
    if (!session) return false;
    session.dispose();
    this.sessions.delete(id);
    return true;
  }

  reapIdle() {
    const now = Date.now();
    for (const [id, session] of this.sessions) {
      if (session.isIdle(now)) {
        session.dispose();
        this.sessions.delete(id);
      }
    }
  }

  disposeAll() {
    clearInterval(this.reaper);
    for (const session of this.sessions.values()) session.dispose();
    this.sessions.clear();
  }
}
