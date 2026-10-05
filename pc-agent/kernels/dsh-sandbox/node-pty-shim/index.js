/**
 * node-pty, for Android.
 *
 * Why this exists: node-pty ships prebuilds for linux/darwin/win32 but not for
 * android-arm64, and its loader asks for `prebuilds/android-arm64/pty.node`
 * first - so on a phone it fails at require() time, which takes the whole DSH
 * plugin tree down with it (dsh-subprocess-local, and through it the shell and
 * the bash tool).
 *
 * What this implements: the same call shape over pipes instead of a
 * pseudo-terminal. Commands run, output streams, exit codes arrive - but there
 * is NO TTY: no interactive prompts, no window size, and a program that checks
 * isatty() will take its non-interactive path. That is stated here rather than
 * pretended away, because "the shell tool works" is a claim about behaviour.
 */
const { EventEmitter } = require('node:events');
const childProcess = require('node:child_process');

class PipePty extends EventEmitter {
  constructor(file, args, options) {
    super();
    this._args = args;
    this._options = options;
    this._child = childProcess.spawn(file, args, {
      cwd: options && options.cwd,
      env: (options && options.env) || process.env,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.pid = this._child.pid;
    this.process = file;
    this._closed = false;
    const push = (chunk) => {
      if (this._closed) return;
      this.emit('data', chunk.toString('utf8'));
    };
    this._child.stdout.on('data', push);
    this._child.stderr.on('data', push);
    this._child.on('error', (err) => {
      this.emit('data', `\r\n[无法启动命令：${err.message}]\r\n`);
    });
    this._child.on('exit', (code, signal) => {
      this._closed = true;
      this.emit('exit', { exitCode: code === null ? -1 : code, signal: signal === null ? 0 : 1 });
    });
  }

  onData(cb) { this.on('data', cb); return { dispose: () => this.off('data', cb) }; }

  onExit(cb) { this.on('exit', cb); return { dispose: () => this.off('exit', cb) }; }

  write(data) { if (!this._closed) this._child.stdin.write(data); }

  /** No TTY means nothing to resize; kept so callers do not have to care. */
  resize() {}

  kill(signal) { if (!this._closed) this._child.kill(signal || 'SIGTERM'); }

  pause() { this._child.stdout.pause(); this._child.stderr.pause(); }

  resume() { this._child.stdout.resume(); this._child.stderr.resume(); }

  clear() {}
}

function spawn(file, args, options) {
  return new PipePty(file, args || [], options || {});
}

module.exports = { spawn };
