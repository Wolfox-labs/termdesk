/**
 * The phone has to know every frame it can be sent, and know each one once.
 *
 * Why this exists. A frame type is written in three places and nothing compared them: the
 * agent's protocol table here, the relay's own two frames (`device.paired`, `node.status`),
 * and a 38-arm `when` on the phone. The cost of that gap is not theoretical — this project
 * shipped a frame the phone never drew, and, in the other direction, two identical
 * `"chat.sent"` arms on the phone, where the second (the one that cleared "sending") was
 * dead code. Kotlin said `Duplicate branch condition` and compiled anyway.
 *
 * Four things are asserted, all of them read from the sources rather than restated here:
 *
 *   1. every frame the agent can send is handled by the phone;
 *   2. no frame name appears twice in the phone's dispatch — the dead-arm bug;
 *   3. the dispatch stays in one place, so (1) and (2) cannot be satisfied by a second copy
 *      elsewhere in the file;
 *   4. every arm the phone has is accounted for: either the agent sends that frame, or the
 *      relay does (and then that claim is checked against the relay's source), or the phone
 *      ignores it on purpose — which is written down with a reason here.
 *
 * (4) is the direction people forget: an arm for a frame nobody sends any more is a claim
 * about a protocol that has moved on.
 *
 * Free, offline, no agent, no model.
 *
 *   node tools/frame-table-test.js
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, '..', '..');
const PROTOCOL = path.join(ROOT, 'pc-agent', 'src', 'protocol.js');
const RELAY_DIR = path.join(ROOT, 'relay', 'src');
const CLIENT = path.join(
  ROOT, 'android', 'app', 'src', 'main', 'java', 'dev', 'termdesk', 'app', 'data', 'AgentClient.kt',
);

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/**
 * Server-to-client frames the phone is expected to *not* act on, with the reason.
 *
 * `hello` is informational: everything the phone needs from a greeting (hostname, protocol
 * verdict) arrives in `auth.ok`, which it does handle. `pong` answers the app-level `ping`,
 * which exists to keep middleboxes from closing an idle socket — the phone learns the link
 * is alive from the socket itself, not from the reply.
 */
const IGNORED = new Map([
  ['hello', 'informational; auth.ok carries hostname and the protocol verdict'],
  ['pong', 'answers app-level ping; liveness comes from the socket'],
]);

/**
 * Frames that reach a phone from the relay rather than from the agent.
 *
 * The relay answers the phone directly for the two things it knows better than the agent:
 * whether the computer is online, and the credential a fresh pairing hands out. They are
 * not in the agent's protocol table because the agent never sends them.
 */
const FROM_RELAY = new Map([
  ['device.paired', 'the relay mints a phone its own credential at pairing time'],
  ['node.status', 'only the relay knows whether the computer behind it is connected'],
]);

/** Frame names the agent declares as server-to-client. */
function agentFrames() {
  const text = fs.readFileSync(PROTOCOL, 'utf8');
  const block = /export const S2C = \{([\s\S]*?)\n\};/.exec(text);
  if (!block) return null;
  const names = [...block[1].matchAll(/^\s*[A-Z_]+:\s*'([^']+)'/gm)].map((m) => m[1]);
  return names.length > 0 ? names : null;
}

/** Whether the relay really sends a frame by this name. Checked, not assumed. */
function relaySends(name) {
  return fs.readdirSync(RELAY_DIR).filter((f) => f.endsWith('.js')).some((file) => {
    const text = fs.readFileSync(path.join(RELAY_DIR, file), 'utf8');
    return new RegExp(`type:\\s*'${name.replace(/\./g, '\\.')}'`).test(text);
  });
}

/**
 * The phone's dispatch, as `{ names, found, region, outside }`.
 *
 * The arms sit one indent level inside the dispatch construct, which is what keeps nested
 * `when`s inside an arm (error codes, say) from being mistaken for frame names. Both shapes
 * count: `"x" -> …` (a `when`) and `"x" to …` (a table).
 */
function phoneDispatch() {
  const lines = fs.readFileSync(CLIENT, 'utf8').split(/\r?\n/);
  const headerIndex = lines.findIndex((l) => /^\s*when \(frame\.optString\("type"\)\) \{/.test(l));
  const isTable = headerIndex < 0;
  const start = isTable
    ? lines.findIndex((l) => /frameHandlers[\s:=]|private val FRAMES|frameTable/i.test(l))
    : headerIndex;
  if (start < 0) return { names: [], found: false, region: '', outside: -1 };

  const indent = (lines[start].match(/^\s*/) ?? [''])[0].length;
  const armRe = new RegExp(`^ {${indent + 4}}"([a-zA-Z._]+)"\\s*(?:->|\\bto\\b)`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (new RegExp(`^ {${indent}}\\}[;)]?\\s*$`).test(lines[i])) { end = i + 1; break; }
  }

  const names = [];
  for (let i = start; i < end; i += 1) {
    const m = armRe.exec(lines[i]);
    if (m) names.push(m[1]);
  }
  const outside = lines.filter((line, i) => (i < start || i >= end) && armRe.test(line)).length;
  return { names, found: names.length > 0, region: `${start + 1}-${end}`, outside };
}

// ---- sources ---------------------------------------------------------------

const declared = agentFrames();
check('the agent\'s server-to-client frame list is readable', Boolean(declared),
  declared ? `${declared.length} frames` : 'S2C not found in protocol.js');

const phone = phoneDispatch();
check('the phone\'s frame dispatch is readable', phone.found,
  phone.found ? `${phone.names.length} arms at lines ${phone.region}` : 'no dispatch found');

// ---- 1. the phone handles what the agent can send to it --------------------

const handled = new Set(phone.names);
const missing = [...new Set(declared ?? [])]
  .filter((name) => !handled.has(name) && !IGNORED.has(name));
check('every frame the agent can send is handled by the phone', missing.length === 0,
  missing.length ? `unhandled: ${missing.join(', ')}` : `${handled.size} names handled`);

// ---- 2. no frame is handled twice (the dead-arm defect) --------------------

const seenNames = new Set();
const duplicates = [];
for (const name of phone.names) {
  if (seenNames.has(name)) duplicates.push(name);
  seenNames.add(name);
}
check('no frame name appears twice in the dispatch', duplicates.length === 0,
  duplicates.length ? `duplicated: ${[...new Set(duplicates)].join(', ')}` : 'one arm per frame');

// ---- 3. and it stays in one place -----------------------------------------

check('every frame arm lives inside that one dispatch', phone.outside === 0,
  phone.outside === 0 ? 'no arms elsewhere in the file' : `${phone.outside} arms outside it`);

// ---- 4. every arm is accounted for, and the claims are checked -------------

const agentSet = new Set(declared ?? []);
const unexplained = phone.names.filter(
  (name) => !agentSet.has(name) && !IGNORED.has(name) && !FROM_RELAY.has(name),
);
check('every arm the phone has is a frame something actually sends', unexplained.length === 0,
  unexplained.length ? `unexplained: ${unexplained.join(', ')}` : 'each arm has a sender');

for (const [name, reason] of FROM_RELAY) {
  check(`the relay really sends "${name}"`, relaySends(name), reason);
  check(`and the phone handles "${name}"`, handled.has(name), 'it is an arm in the dispatch');
}

for (const [name, reason] of IGNORED) {
  check(`the exception "${name}" still exists somewhere`, agentSet.has(name) || handled.has(name),
    reason);
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
