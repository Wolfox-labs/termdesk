/**
 * Built-in Cloudflare tunnel.
 *
 * The phone should not have to know whether it is on the home Wi-Fi, on mobile
 * data or on a VPN: it should have one address that works from anywhere. So the
 * agent can bring up its own cloudflared process and hand out that address.
 *
 * Three shapes, all spoken here:
 *   quick   no account, no DNS: Cloudflare assigns a random trycloudflare.com
 *           hostname. That randomness is the price of "works anywhere with no
 *           setup" — the address changes when the tunnel restarts.
 *   named   a tunnel token plus a hostname in ~/.termdesk/cloudflared.json, which
 *           Cloudflare already routes to this machine. Stable address.
 *   config  a cloudflared config file that already exists on the machine
 *           (~/.cloudflared/<name>-config.yml): tunnel id, credentials file and
 *           one ingress rule per hostname. This is the shape 'cloudflared tunnel
 *           create' leaves behind, so a machine that already runs a tunnel needs
 *           no new account and no new secret — the agent just runs that file,
 *           and reads its hostname from the rule that points at this port.
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

/** Parse the port out of an ingress `service` value. */
function servicePort(service) {
  if (!service) return null;
  const match = /:(\d+)\/?$/.exec(service);
  return match ? Number(match[1]) : null;
}

/** The ingress rules in a cloudflared config, as far as TermDesk needs them. */
function readIngress(file) {
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); } catch { return []; }
  const entries = [];
  let current = null;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/#.*$/, '');
    const hostname = /^\s*-?\s*hostname:\s*(\S+)/.exec(line);
    if (hostname) {
      current = { hostname: hostname[1], service: null };
      entries.push(current);
      continue;
    }
    const service = /^\s*service:\s*(\S+)/.exec(line);
    if (service && current && !current.service) current.service = service[1];
  }
  return entries;
}

/**
 * A cloudflared config file that already exists on this machine.
 *
 * Preferred over a token because it is what 'cloudflared tunnel create'
 * produces: the tunnel id, its credentials file and the hostname routing all
 * live in one file the user already owns, so nothing has to be copied into
 * TermDesk. The parser is a line scanner on purpose — the file is generated in a
 * stable shape, and a YAML dependency for four fields would be worse than
 * twenty honest lines.
 */
export function findTunnelConfig() {
  const explicit = process.env.TERMDESK_TUNNEL_CONFIG;
  if (explicit && fs.existsSync(explicit)) return explicit;

  const dir = path.join(os.homedir(), '.cloudflared');
  for (const name of ['termdesk-config.yml', 'termdesk.yml', 'config.yml']) {
    const candidate = path.join(dir, name);
    if (fs.existsSync(candidate)) return candidate;
  }
  try {
    const found = fs.readdirSync(dir)
      .filter((name) => /\.ya?ml$/i.test(name))
      .map((name) => path.join(dir, name))
      .filter((file) => readIngress(file).some((entry) => entry.hostname));
    return found[0] ?? null;
  } catch {
    return null;
  }
}

/**
 * Pick the hostname that routes to THIS agent.
 *
 * One tunnel often serves several things (this machine's config also routes a
 * DSH web UI), so the rule is the service port — never "the first hostname in
 * the file".
 */
export function hostnameForPort(file, port) {
  const entries = readIngress(file).filter((entry) => entry.hostname);
  if (entries.length === 0) return null;
  const exact = entries.find((entry) => servicePort(entry.service) === Number(port));
  return (exact ?? entries[0]).hostname;
}

/**
 * Prove the public address actually reaches this agent.
 *
 * "Registered tunnel connection" is only half the story: the DNS route, the
 * ingress rule and the proxy can each still be wrong. One request to the public
 * /healthz turns all three into a yes/no before any QR code is shown.
 */
export async function verifyPublic({ url, timeoutMs = 12000 } = {}) {
  if (!url) return { ok: false, error: '没有可验证的地址' };
  const target = url.replace(/\/$/, '') + '/healthz';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(target, { signal: controller.signal, redirect: 'follow' });
    const body = await response.text().catch(() => '');
    return { ok: response.ok, status: response.status, body: body.slice(0, 120), target };
  } catch (err) {
    return { ok: false, error: String(err?.message ?? err), target };
  } finally {
    clearTimeout(timer);
  }
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
      /** A stable address means the phone never has to be re-paired. */
      stable: this.mode === 'named' || this.mode === 'config',
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
    const configFile = config?.token ? null : findTunnelConfig();
    const configHost = configFile ? hostnameForPort(configFile, port) : null;
    this.mode = config?.token ? 'named' : (configFile && configHost ? 'config' : 'quick');
    const args = config?.token
      ? ['tunnel', '--no-autoupdate', 'run', '--token', config.token]
      : this.mode === 'config'
        ? ['tunnel', '--no-autoupdate', '--config', configFile, 'run']
        : ['tunnel', '--no-autoupdate', '--url', `http://127.0.0.1:${port}`];
    /** Hostname from the config file; the token path carries its own. */
    const namedHost = config?.hostname ?? configHost;

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
        const named = namedHost;
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
