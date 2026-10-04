/**
 * ACP handshake probe — metadata only, never a model call.
 *
 * Spawns a kernel's ACP server, runs `initialize`, and reports what it declares:
 *
 *   loadSession     it can replay a past transcript when a session is opened
 *   session/list    it can list persisted sessions
 *   session/resume  it can continue a persisted session in a new process
 *   fork            it can branch a session
 *
 * Results are cached per binary path: a picker refresh must not spawn a kernel
 * every time, and some spawns take seconds.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const CACHE_TTL_MS = 10 * 60_000;
const cache = new Map();

function cacheKey(entry) {
  let stamp = 0;
  try { stamp = fs.statSync(entry.path).mtimeMs; } catch { /* keep 0 */ }
  return `${entry.id}:${entry.path}:${stamp}`;
}

export async function probeAcpServer(entry, { args = ['acp'], timeoutMs = 20_000 } = {}) {
  const key = cacheKey(entry);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.value;

  const value = await handshake(entry, args, timeoutMs);
  cache.set(key, { at: Date.now(), value });
  return value;
}

function handshake(entry, args, timeoutMs) {
  return new Promise((resolve) => {
    const shell = process.platform === 'win32' && !/\.exe$/i.test(entry.path);
    let child;
    try {
      child = spawn(entry.path, args, { stdio: ['pipe', 'pipe', 'pipe'], shell, windowsHide: true });
    } catch (err) {
      resolve({ ok: false, error: String(err?.message ?? err) });
      return;
    }
    let buffer = '';
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { child.kill(); } catch { /* already gone */ }
      resolve(result);
    };
    const timer = setTimeout(() => finish({ ok: false, error: 'ACP 握手超时' }), timeoutMs);

    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      buffer += chunk;
      let index;
      while ((index = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (!line) continue;
        let message;
        try { message = JSON.parse(line); } catch { continue; }
        if (message.id !== 1) continue;
        if (message.error) { finish({ ok: false, error: String(message.error.message ?? 'initialize 失败') }); return; }
        const caps = message.result?.agentCapabilities ?? {};
        const session = caps.sessionCapabilities ?? {};
        finish({
          ok: true,
          protocolVersion: message.result?.protocolVersion ?? null,
          agent: message.result?.agentInfo?.name ?? null,
          version: message.result?.agentInfo?.version ?? null,
          loadSession: Boolean(caps.loadSession),
          sessionList: 'list' in session,
          sessionResume: 'resume' in session,
          sessionClose: 'close' in session,
          fork: 'fork' in session,
          image: Boolean(caps.promptCapabilities?.image),
          embeddedContext: Boolean(caps.promptCapabilities?.embeddedContext),
        });
        return;
      }
    });
    child.on('error', (err) => finish({ ok: false, error: String(err?.message ?? err) }));
    child.on('exit', () => finish({ ok: false, error: 'ACP 进程提前退出' }));

    const initialize = {
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: 1,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
        clientInfo: { name: 'termdesk-discovery', version: '0.1' },
      },
    };
    try { child.stdin.write(JSON.stringify(initialize) + '\n'); } catch { finish({ ok: false, error: 'ACP stdin 不可写' }); }
  });
}
