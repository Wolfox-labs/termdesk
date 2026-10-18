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

// --- queue state must reach the phone ---------------------------------------
{
  const { ChatManager } = await import('../src/chat.js');
  process.env.TERMDESK_CHAT_PROVIDER = process.env.TERMDESK_CHAT_PROVIDER || 'deepseek-official';
  process.env.TERMDESK_CHAT_MODEL = process.env.TERMDESK_CHAT_MODEL || 'deepseek-flash';
  const manager = new ChatManager();
  try {
    const created = manager.create({ engine: 'dsh', cwd: tmp });
    const chat = manager.get(created.chat.id);
    // A live-attached chat that cannot find a journal must not add a SECOND copy
    // of the user's line: send() already put it on screen.
    chat.liveHost = { pid: 0, port: 1, authority: '127.0.0.1:1' };
    await manager.send(chat.id, 'one line only');
    const copies = chat.events.filter((e) => e.kind === 'message' && e.role === 'user'
      && e.text === 'one line only').length;
    check('a live send does not duplicate the user line send() already showed',
      copies === 1, copies + ' copies');

    // Now the queue events the desktop records.
    chat.status = 'running';
    chat.livePendingText = 'waiting there';
    chat.livePendingLine = chat.events[chat.events.length - 1];
    chat.liveQueued = false;
    chat.liveEchoSeen = false;
    manager.handleLiveInbox(chat, {
      type: 'agent/inbox/spliced',
      data: { target: 'next-turn', inserted: [{ content: [{ type: 'text', text: 'waiting there' }] }] },
    });
    check('an insertion is reported as waiting on the desktop', chat.liveQueued === true);
    check('and the chat says so in words',
      chat.events.some((e) => e.role === 'engine' && /排在电脑端当前回合之后/.test(e.text ?? '')));

    manager.handleLiveInbox(chat, {
      type: 'agent/inbox/spliced',
      data: { target: 'next-turn', removedCount: 1, inserted: [], outcome: 'canceled' },
    });
    check('deleting it on the desktop returns the chat to idle', chat.status === 'idle', chat.status);
    check('and says the message will not be sent',
      chat.events.some((e) => e.role === 'engine' && /不会再发出去/.test(e.text ?? '')));
    check('the pending state is cleared', chat.livePendingText === null && chat.liveQueued === false);
  } finally {
    manager.disposeAll();
  }
}
// --- token-level streaming ---------------------------------------------------
{
  const { ChatManager } = await import('../src/chat.js');
  process.env.TERMDESK_CHAT_PROVIDER = process.env.TERMDESK_CHAT_PROVIDER || 'deepseek-official';
  process.env.TERMDESK_CHAT_MODEL = process.env.TERMDESK_CHAT_MODEL || 'deepseek-flash';
  const manager = new ChatManager();
  try {
    const created = manager.create({ engine: 'dsh', cwd: tmp });
    const chat = manager.get(created.chat.id);
    // The app's stream sends the same chunk shapes the resident runtime does, so
    // the preview rules are reused: deltas grow ONE record that the phone replaces.
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'block-start', index: 0, blockType: 'text' } });
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'text-delta', index: 0, text: '你' } });
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'text-delta', index: 0, text: '好' } });
    const preview = chat.events[chat.events.length - 1];
    check('live text deltas grow one streaming record',
      preview?.kind === 'message' && preview?.role === 'assistant' && preview?.streaming === true && preview?.text === '你好',
      JSON.stringify({ kind: preview?.kind, text: preview?.text, streaming: preview?.streaming }));
    const before = chat.events.length;
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'text-delta', index: 0, text: '！' } });
    check('a later delta replaces the same record instead of adding a line',
      chat.events.length === before && chat.events[chat.events.length - 1].text === '你好！');

    // Reasoning deltas are a different block and must not glue onto the answer.
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'block-start', index: 1, blockType: 'reasoning' } });
    manager.handleLiveChunk(chat, { type: 'chunk', chunk: { type: 'reasoning-delta', index: 1, text: '想一想' } });
    const reasoning = chat.events[chat.events.length - 1];
    check('reasoning deltas land in their own record',
      reasoning?.kind === 'reasoning' && reasoning?.text === '想一想', JSON.stringify({ kind: reasoning?.kind, text: reasoning?.text }));
  } finally {
    manager.disposeAll();
  }
}

if (host) {
  const { followSession } = await import('../src/kernels/dsh-live.js');
  const listed = await liveCall(host, 'session/list', { _request: {} });
  const live = listed.ok ? listed.value.items.find((s) => s.agentAvailable) : null;
  if (live) {
    const snapshot = await new Promise((resolve) => {
      const handle = followSession(host, {
        sessionId: live.sessionId,
        onSnapshot: resolve,
        onEvent: () => {},
        onChunk: () => {},
        onError: () => resolve(null),
        onClose: () => resolve(null),
      });
      setTimeout(() => { handle.close(); resolve(null); }, 6000);
    });
    check('the live stream opens with a snapshot carrying a cursor and an assistant baseline',
      Boolean(snapshot) && typeof snapshot.cursor === 'number' && typeof snapshot.assistantStream?.revision === 'number',
      snapshot ? 'cursor ' + snapshot.cursor + ' rev ' + snapshot.assistantStream?.revision : 'no snapshot');
  }
}
// --- the desktop's own slash commands, reachable from the phone ---------------
if (host) {
  const { liveExecuteCommand, liveListCommands } = await import('../src/kernels/dsh-live.js');
  const listed2 = await liveCall(host, 'session/list', { _request: {} });
  const live = listed2.ok ? listed2.value.items.find((s) => s.agentAvailable) : null;
  if (live) {
    const catalog = await liveListCommands(host, live.sessionId);
    check('the host command catalog is readable', catalog.ok && catalog.commands.length > 0,
      catalog.ok ? catalog.commands.map((c) => c.name).join(',') : catalog.message);
    check('it contains the commands the phone was missing',
      catalog.commands.some((c) => c.name === 'compact') && catalog.commands.some((c) => c.name === 'export'));

    // /compact with-args is the documented usage error: it proves the command ran
    // in the host without touching history and without a model request.
    const usage = await liveExecuteCommand(host, live.sessionId, '/compact with-args');
    check('a command executes in the host without a model turn',
      usage.ok === true && /Usage: \/compact/.test(usage.text ?? ''), usage.kind + ': ' + (usage.text ?? usage.message));

    const { ChatManager } = await import('../src/chat.js');
    process.env.TERMDESK_CHAT_PROVIDER = process.env.TERMDESK_CHAT_PROVIDER || 'deepseek-account';
    process.env.TERMDESK_CHAT_MODEL = process.env.TERMDESK_CHAT_MODEL || 'deepseek-flash';
    const manager = new ChatManager();
    try {
      const resumed = await manager.resume({ engine: 'dsh', id: live.sessionId });
      const chat = resumed.ok ? manager.get(resumed.chat.id) : null;
      const sent = chat ? await manager.send(chat.id, '/compact with-args') : { ok: false };
      check('a slash command from the phone runs in the live session, not as a prompt',
        sent.ok === true && sent.queued !== true
          && chat.status === 'idle'
          && chat.events.some((e) => /Usage: \/compact/.test(e.text ?? '')),
        sent.ok ? 'status ' + chat.status : sent.message);
    } finally {
      manager.disposeAll();
    }
  }
}
fs.rmSync(tmp, { recursive: true, force: true });
const failed = results.filter((r) => !r.passed).length;
console.log(`\nDSH live attach: ${results.length - failed}/${results.length} passed`);
process.exit(failed === 0 ? 0 : 1);
