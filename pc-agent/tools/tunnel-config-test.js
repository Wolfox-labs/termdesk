/**
 * Tunnel selection: a machine that already owns a cloudflared config must get a
 * stable address from it, with no new account and no secret copied into
 * TermDesk.
 *
 * These checks read this machine's real ~/.cloudflared directory and a few
 * synthetic files. Nothing is spawned and no network request is made except one
 * deliberately refused connection (to prove verifyPublic reports failure
 * instead of throwing).
 *
 *   node tools/tunnel-config-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { findTunnelConfig, hostnameForPort, verifyPublic, findCloudflared } from '../src/tunnel.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- synthetic configs -----------------------------------------------------
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-tunnel-'));
const sample = path.join(tmp, 'sample.yml');
fs.writeFileSync(sample, [
  '# a comment with hostname: not-a-host',
  'tunnel: 11111111-2222-3333-4444-555555555555',
  'credentials-file: C:\\creds.json',
  'ingress:',
  '  - hostname: first.example.com',
  '    service: http://127.0.0.1:3080',
  '    originRequest:',
  '      keepAliveTimeout: 30s',
  '  - hostname: second.example.com',
  '    service: http://127.0.0.1:7420',
  '  - service: http_status:404',
  '',
].join('\n'));

check('picks the hostname whose service port matches', hostnameForPort(sample, 7420) === 'second.example.com',
  String(hostnameForPort(sample, 7420)));
check('does not just take the first hostname', hostnameForPort(sample, 3080) === 'first.example.com');
check('falls back to the first hostname for an unknown port', hostnameForPort(sample, 9999) === 'first.example.com');
check('a comment is not parsed as a hostname', hostnameForPort(sample, 3080) !== 'not-a-host');
check('a missing file yields null', hostnameForPort(path.join(tmp, 'nope.yml'), 7420) === null);

const empty = path.join(tmp, 'empty.yml');
fs.writeFileSync(empty, 'tunnel: x\ningress:\n  - service: http_status:404\n');
check('a config with no hostname is not a tunnel address', hostnameForPort(empty, 7420) === null);

// --- this machine ----------------------------------------------------------
const found = findTunnelConfig();
console.log(`config file on this machine: ${found ?? '(none)'}`);
check('either a config is found or the answer is null', found === null || fs.existsSync(found));
if (found) {
  const host = hostnameForPort(found, 7420);
  check('the local config yields a hostname for the agent port', typeof host === 'string' && host.includes('.'),
    String(host));
  check('TERMDESK_TUNNEL_CONFIG overrides discovery', (() => {
    process.env.TERMDESK_TUNNEL_CONFIG = sample;
    const overridden = findTunnelConfig();
    delete process.env.TERMDESK_TUNNEL_CONFIG;
    return overridden === sample;
  })());
}
check('cloudflared binary is resolvable or explicitly missing',
  findCloudflared() === null || fs.existsSync(findCloudflared()), String(findCloudflared()));

// --- verification ----------------------------------------------------------
const refused = await verifyPublic({ url: 'http://127.0.0.1:1', timeoutMs: 3000 });
check('an unreachable address reports failure instead of throwing', refused.ok === false, refused.error ?? String(refused.status));
check('no url is refused up front', (await verifyPublic({})).ok === false);

fs.rmSync(tmp, { recursive: true, force: true });
const failures = results.filter((r) => !r.passed).length;
console.log(`\nTunnel config: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);