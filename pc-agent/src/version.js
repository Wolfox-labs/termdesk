/**
 * Version negotiation between the phone app and the PC agent.
 *
 * Why this exists: `encodeFrame` puts `v` (the protocol version) on every frame
 * and `auth.ok` has always carried `protocol` and `agent`, but nobody ever read
 * them. So when the two sides disagreed, nothing said so — the phone kept talking
 * a protocol the agent no longer spoke, and the symptom was a feature that
 * silently did nothing. That is the one failure mode a protocol version is
 * supposed to prevent.
 *
 * The rules, in the order they matter:
 *
 *   1. A client that does not declare a version is treated as v1. The field is
 *      new; an app built before it must keep working, not be locked out by its
 *      own absence. This is the compatibility promise that makes the check
 *      safe to add at all.
 *   2. A client below `MIN_SUPPORTED` is refused, and told to upgrade the app.
 *      Its frames are the ones this agent can no longer answer correctly.
 *   3. A client above `PROTOCOL_VERSION` is allowed through. We are the older
 *      side then; the newcomer is responsible for staying compatible with us,
 *      and refusing it would mean every future app release breaks against an
 *      agent nobody updated. `upgrade: 'agent'` still says so.
 *   4. Inside the band: exactly equal means exact, anything else is tolerated.
 *
 * Pure on purpose: no socket, no logging, no clock. `tools/protocol-negotiation-test.js`
 * pins each rule, including the two that are easy to get wrong (the absent
 * version, and the newer client).
 *
 * One thing here cannot be shared across the wire: the phone's copy of
 * `PROTOCOL_VERSION` lives in `AgentClient.kt` (Kotlin cannot import this file).
 * If the two ever disagree the mismatch is *visible* — the phone says which side
 * to update — but a bump still has to be made in both places.
 */

import { PROTOCOL_VERSION } from './protocol.js';

/**
 * Oldest client protocol this agent can still serve correctly.
 *
 * It only moves when a change genuinely breaks older clients. Raising it locks
 * out installed apps, so it is a product decision, not a cleanup.
 */
export const MIN_SUPPORTED_PROTOCOL = 1;

/** Parse a declared version. Anything that is not a positive integer is null. */
export function parseVersion(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number.parseInt(String(value), 10);
  if (!Number.isFinite(n)) return null;
  const int = Math.trunc(n);
  return int >= 1 ? int : null;
}

/**
 * Judge a client's declared protocol version.
 *
 * @param {unknown} declared whatever the client put in `auth.v` (may be absent)
 * @param {number} [floor] the minimum to enforce; defaults to MIN_SUPPORTED_PROTOCOL.
 *   Injectable so a future bump is testable, and so the refusal path can be
 *   exercised without pretending an installed app declares v0 (which the parser
 *   reads as "declared nothing", exactly the compatible case).
 * @returns {{ok: boolean, clientV: number, serverV: number, minV: number,
 *            upgrade: 'none'|'app'|'agent', reason: string|null}}
 */
export function judgeClientVersion(declared, floor = MIN_SUPPORTED_PROTOCOL) {
  const serverV = PROTOCOL_VERSION;
  const minV = floor;
  const parsed = parseVersion(declared);
  const clientV = parsed ?? 1;

  if (clientV < minV) {
    return {
      ok: false,
      clientV,
      serverV,
      minV,
      upgrade: 'app',
      reason: `app_too_old: 手机端协议 v${clientV}，这台电脑最低要求 v${minV}（当前 v${serverV}）。请升级手机上的 TermDesk。`,
    };
  }
  return {
    ok: true,
    clientV,
    serverV,
    minV,
    // Above our floor but newer than us: it will work, and the person should know
    // which side is behind.
    upgrade: clientV > serverV ? 'agent' : 'none',
    reason: null,
  };
}

/**
 * Judge the agent's answer, from the phone's side.
 *
 * The phone has no floor of its own to enforce yet (it is the oldest thing in
 * the field), so this reports rather than refuses: a mismatch where the agent is
 * older means features may silently not work, and the screen can say which side
 * to update instead of leaving the person to guess.
 *
 * @param {unknown} agentProtocol value of `auth.ok.protocol`
 * @param {unknown} agentMin value of `auth.ok.minV` (may be absent on old agents)
 * @returns {{ok: boolean, agentV: number|null, agentMin: number|null,
 *            upgrade: 'none'|'app'|'agent', reason: string|null}}
 */
export function judgeAgentVersion(agentProtocol, agentMin) {
  const mine = PROTOCOL_VERSION;
  const agentV = parseVersion(agentProtocol);
  const minV = parseVersion(agentMin);

  // An agent so old it does not say: treat it as v1, which is what it is.
  if (agentV === null) {
    return { ok: true, agentV: null, agentMin: minV, upgrade: 'agent', reason: null };
  }
  // It explicitly requires something newer than this app speaks.
  if (minV !== null && minV > mine) {
    return {
      ok: false,
      agentV,
      agentMin: minV,
      upgrade: 'app',
      reason: `app_too_old: 这台电脑要求协议 v${minV}，本机 App 是 v${mine}。请升级手机上的 TermDesk。`,
    };
  }
  if (agentV > mine) {
    return { ok: true, agentV, agentMin: minV, upgrade: 'app', reason: null };
  }
  if (agentV < mine) {
    return { ok: true, agentV, agentMin: minV, upgrade: 'agent', reason: null };
  }
  return { ok: true, agentV, agentMin: minV, upgrade: 'none', reason: null };
}
