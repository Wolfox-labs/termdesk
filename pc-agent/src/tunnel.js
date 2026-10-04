/**
 * Built-in Cloudflare tunnel.
 *
 * The phone should not have to know whether it is on the home Wi-Fi, on mobile
 * data or on a VPN: it should have one address that works from anywhere. So the
 * agent can bring up its own cloudflared process and hand out that address.
 *
 * Two shapes, both spoken here:
 *   quick  no account, no DNS: Cloudflare assigns a random trycloudflare.com
 *          hostname. That randomness is the price of "works anywhere with no
 *          setup" — the address changes when the tunnel restarts.
 *   named  a tunnel token plus a hostname in ~/.termdesk/cloudflared.json, which
 *          Cloudflare already routes to this machine. Stable address, needs an
 *          account.
 *
 * Cloudflare terminates TLS and forwards WebSocket upgrades, so the phone talks
 * `wss://` to the same port it would have used locally.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const CONFIG_PATH = path.join(os.homedir(), '.termdesk', 'cloudflared.json');
const QUICK_URL = /https:\/\/[a-z0-9][a-z0-9-]*\.trycloudflare\.com/i;

/** Where the bundled binary is, or an explicitly configured one. */
export function findCloudflared() {
  const candidates = [
    process.env.TERMDESK_CLOUDFLARED,
    path.join(REPO_ROOT, 'tools', 'cloudflared-windows-amd64.exe'),
    path.join(REPO_ROOT, 'tools', 'cloudflared'),
    '/usr/local/bin/cloudflared',
    '/usr/bin/cloudflared',
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      if (fs.statSync(candidate).isFile()) return candidate;
    } catch {
      // keep looking
    }
  }
  return null;
}

/** Optional named-tunnel settings: { token, hostname }. */
export function tunnelConfig() {
  try {
    const raw = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
    if (typeof raw?.token === 'string' && raw.token.length > 0) {
      return {
        token: raw.token,
        hostname: typeof raw?.hostname === 'string' ? raw.hostname : null,
      };
    }
  } catch {
    // no config, or an unreadable one: fall back to a quick tunnel
  }
  return null;
}

export function tunnelConfigPath() {
  return CONFIG_PATH;
}

export class Tunnel {
  constructor() {
    this.child = null;
    this.url = null;
    this.mode = null;
    this.error = null;
    this.startedAt = null;
    /** Last few cloudflared lines, for the pairing page and the log. */
    this.tail = [];
  }

  status() {
    return {
      running: Boolean(this.child),
      url: this.url,
      mode: this.mode,
      error: this.error,
      startedAt: this.startedAt,
    };
  }

  /**
   * Start the tunnel and resolve once the public URL is known.
   *
   * Rejects on timeout or on a cloudflared error line: a tunnel that never
   * connects must not look like it succeeded, or the QR code would carry an
   * address that goes nowhere.
   */
  start({ port, timeoutMs = 60000 } = {}) {
    if (this.child) return Promise.resolve(this.status());
    const bin = findCloudflared();
    if (!bin) {
      this.error = 'cloudflared 未找到（可放在 tools/ 或设置 TERMDESK_CLOUDFLARED）';
      return Promise.reject(new Error(this.error));
    }
    const config = tunnelConfig();
    this.mode = config?.token ? 'named' : 'quick';
    const args = config?.token
      ? ['tunnel', '--no-autoupdate', 'run', '--token', config.token]
      : ['tunnel', '--no-autoupdate', '--url', `http://127.0.0.1:${port}`];

    return new Promise((resolve, reject) => {
      let settled = false;
      const child = spawn(bin, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
      this.child = child;
      this.startedAt = Date.now();

      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        this.error = 'cloudflared 未在预期时间内给出地址';
        reject(new Error(this.error));
      }, timeoutMs);

      const onLine = (line) => {
        const text = line.trim();
        if (text.length === 0) return;
        this.tail.push(text);
        if (this.tail.length > 12) this.tail.shift();
        if (settled) return;
        const named = config?.hostname;
        if (named && /Registered tunnel connection|Connection .* registered/i.test(text)) {
          settled = true;
          clearTimeout(timer);
          this.url = `https://${named}`;
          resolve(this.status());
          return;
        }
        const quick = text.match(QUICK_URL);
        if (quick) {
          settled = true;
          clearTimeout(timer);
          this.url = quick[0];
          resolve(this.status());
        }
      };

      const consume = (stream) => {
        let buffer = '';
        stream.on('data', (chunk) => {
          buffer += chunk.toString('utf8');
          const lines = buffer.split(/\r?\n/);
          buffer = lines.pop() ?? '';
          for (const line of lines) onLine(line);
        });
      };
      consume(child.stdout);
      consume(child.stderr);

      child.on('error', (err) => {
        this.error = `cloudflared 启动失败：${err.message}`;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(new Error(this.error));
        }
      });

      child.on('close', (code) => {
        this.child = null;
        this.url = null;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          this.error = `cloudflared 退出（退出码 ${code}）`;
          reject(new Error(this.error));
        } else {
          this.error = `cloudflared 已退出（退出码 ${code}）`;
        }
      });
    });
  }

  stop() {
    if (this.child) {
      this.child.kill();
      this.child = null;
    }
    this.url = null;
  }
}
