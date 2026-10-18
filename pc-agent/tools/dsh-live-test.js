/**
 * Attaching to the desktop app that is already running.
 *
 * DSH allows one writer per session, and the desktop app holds that handle while
 * a conversation is open. The only honest way to continue the same conversation
 * from the phone is therefore to talk to the app's own agent, which this module
 * does over the app's local `/api` with the owner cookie its window uses.
 *
 * What is pinned here:
 *   - the cookie is exactly the format the installed 0.2.1-rc.1 client mints
 *     (name from the authority, HMAC over the payload, 30-day window);
 *   - the credential is read from the harness store and never invented; a missing
 *     record means "no live host", not a fake one;
 *   - the journal tail decodes appended zstd frames, including a frame that is
 *     still being written, and stops when asked;
 *   - against a running desktop app (when present) the live session list answers
 *     and a blank prompt is refused by the app itself — that refusal proves the
 *     endpoint is reachable WITHOUT spending a model request.
 *
 *   node tools/dsh-live-test.js
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

import {
  browserSessionSecret,
  discoverLiveHost,
  liveCall,
  livePrompt,
  mintCookie,
  tailJournal,
} from '../src/kernels/dsh-live.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- credentials and cookie --------------------------------------------------
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-dsh-live-'));
const priorDshDir = process.env.TERMDESK_DSH_DIR;
process.env.TERMDESK_DSH_DIR = tmp;

const secretBytes = crypto.randomBytes(32);
const secretB64 = secretBytes.toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
fs.writeFileSync(path.join(tmp, '.credentials.yaml'), [
  'version: 1',
  'refs: {}',
  'records:',
  '  client-connection/browser-session:',
  '    kind: grant',
  '    payload:',
  '      version: 1',
  `      secret: ${secretB64}`,
  '',
].join('\n'));

check('the browser-session secret is read from the harness credential store',
  browserSessionSecret()?.length === 32);
check('a missing record is "no host", not a guessed secret',
  (() => { process.env.TERMDESK_DSH_DIR = path.join(tmp, 'nowhere'); const got = browserSessionSecret(); process.env.TERMDESK_DSH_DIR = tmp; return got === null; })());

const fixedNow = 1_700_000_000_000;
const authority = '127.0.0.1:61869';
const cookie = mintCookie(secretBytes, authority, fixedNow);
check('the cookie name is authority-scoped', cookie.name === 'dsh-auth-' + Buffer.from(
  crypto.createHash('sha256').update(authority).digest(),
).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, ''));
check('the cookie value is v1.payload.signature', /^v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(cookie.value));
{
  const [version, body, signature] = cookie.value.split('.');
  const payload = JSON.parse(Buffer.from(body.replaceAll('-', '+').replaceAll('_', '/'), 'base64').toString('utf8'));
  const expected = crypto.createHmac('sha256', secretBytes).update(body).digest('base64')
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
  check('the payload binds this authority and a 30-day window',
    version === 'v1' && payload.authority === authority
      && payload.expiresAt - payload.issuedAt === 30 * 86_400_000);
  check('the signature verifies against the stored secret', signature === expected);
}

// --- journal tail ------------------------------------------------------------
{
  const file = path.join(tmp, 'journal.zstd');
  const frame = (lines) => zlib.zstdCompressSync(Buffer.from(lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8'));
  fs.writeFileSync(file, Buffer.concat([frame([{ type: 'turn/start', seq: 1 }])]));
  const seen = [];
  const tail = tailJournal(file, { fromOffset: 0, onEvent: (ev) => seen.push(ev.type), pollMs: 20 });
  await new Promise((r) => setTimeout(r, 80));
  fs.appendFileSync(file, frame([{ type: 'user/message', seq: 2 }, { type: 'turn/end', seq: 3 }]));
  await new Promise((r) => setTimeout(r, 160));
  tail.stop();
  await tail.done;
  check('appended frames are decoded in order',
    seen.join(',') === 'turn/start,user/message,turn/end', seen.join(','));

  // A half-written frame must not be emitted, and must be picked up later.
  const file2 = path.join(tmp, 'torn.zstd');
  const whole = frame([{ type: 'assistant/message', seq: 9 }]);
  // Start torn: a session being written right now ends in a partial frame.
  fs.writeFileSync(file2, whole.subarray(0, whole.length - 5));
  const seen2 = [];
  const tail2 = tailJournal(file2, { fromOffset: 0, onEvent: (ev) => seen2.push(ev.type), pollMs: 20 });
  await new Promise((r) => setTimeout(r, 100));
  const beforeRepair = seen2.length;
  fs.writeFileSync(file2, whole);
  await new Promise((r) => setTimeout(r, 160));
  tail2.stop();
  await tail2.done;
  check('a torn frame is retried rather than reported',
    beforeRepair === 0 && seen2.join(',') === 'assistant/message', `${beforeRepair}/${seen2.join(',')}`);
}

// --- against the real desktop app (when it is running) -----------------------
if (priorDshDir === undefined) delete process.env.TERMDESK_DSH_DIR;
else process.env.TERMDESK_DSH_DIR = priorDshDir;
const host = discoverLiveHost({ fresh: true });
if (host) {
  console.log(`\n(desktop host found on 127.0.0.1:${host.port})`);
  const listed = await liveCall(host, 'session/list', { _request: {} });
  check('the live session list answers over the owner cookie',
    listed.ok === true && Array.isArray(listed.value?.items) && listed.value.items.length > 0,
    listed.ok ? `${listed.value.items.length} sessions` : listed.message);
  check('live list reports whether each agent is running',
    listed.ok && listed.value.items.every((s) => typeof s.running === 'boolean' && typeof s.agentAvailable === 'boolean'));

  // Blank content is refused BEFORE the app resolves an agent or appends
  // anything, so this proves the endpoint is reachable without a model request.
  const blank = await livePrompt(host, { sessionId: 'termdesk-live-test-missing', text: '   ' });
  check('a blank prompt is refused by the app (no model request is made)',
    blank.ok === false && blank.code === 'gateway/bad-request', `${blank.code}: ${blank.message}`);

  const routed = await fetch(`http://${host.authority}/api/session/list`, { method: 'POST' });
  check('an unauthenticated call is refused', routed.status === 401, String(routed.status));
} else {
  console.log('SKIP  live host checks: no DeepSeek Harness desktop app is running');
}

// --- the chat pipeline prefers the live agent over a second writer -----------
if (host) {
  const { ChatManager } = await import('../src/chat.js');
  process.env.TERMDESK_CHAT_PROVIDER = process.env.TERMDESK_CHAT_PROVIDER || 'deepseek-account';
  process.env.TERMDESK_CHAT_MODEL = process.env.TERMDESK_CHAT_MODEL || 'deepseek-flash';
  const listed = await liveCall(host, 'session/list', { _request: {} });
  const live = listed.ok ? listed.value.items.find((s) => s.agentAvailable) : null;
  if (live) {
    const manager = new ChatManager();
    try {
      const resumed = await manager.resume({ engine: 'dsh', id: live.sessionId });
      check('opening a running session attaches to the desktop agent',
        resumed.ok === true && resumed.chat?.liveAttached === true, resumed.ok ? 'liveAttached' : resumed.message);
      check('an attached chat does not claim a second writer',
        resumed.ok && !resumed.note, resumed.note ?? '(no note)');
      check('the attached chat shows the recorded conversation',
        resumed.ok && (resumed.chat.events?.length ?? 0) > 0, `${resumed.chat?.events?.length ?? 0} events`);
    } finally {
      manager.disposeAll();
    }
  }
}

fs.rmSync(tmp, { recursive: true, force: true });
const failed = results.filter((r) => !r.passed).length;
console.log(`\nDSH live attach: ${results.length - failed}/${results.length} passed`);
process.exit(failed === 0 ? 0 : 1);
