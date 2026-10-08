/**
 * Matching an answer to the question that asked for it.
 *
 * Why: a reply used to carry no identity, so the phone matched answers to questions
 * by hope. `fs.list A` then `fs.list B` over a slow link can come back in the other
 * order, and the screen then showed A's contents under B's name — wrong, plausible
 * looking, and impossible for the person to explain. A lost request was equally
 * invisible: the view simply stayed on the previous directory for ever.
 *
 * The PHONE mints the id and the agent echoes it back, because the phone is the
 * side that knows what it is waiting for. The agent does not interpret it, and does
 * not require it: an older client that sends none keeps working exactly as before,
 * which is why this is a widening of the protocol rather than a new version.
 *
 * Pure functions, so `tools/call-id-test.js` can pin the echo and the error path
 * without a socket.
 */

/**
 * The id to echo, or null when the client did not send a usable one.
 *
 * Blank and whitespace-only count as absent: echoing `""` would let the phone
 * claim an answer it cannot match, which is the bug this exists to prevent.
 */
export function callIdOf(frame) {
  const id = frame?.callId;
  return typeof id === 'string' && id.trim().length > 0 ? id : null;
}

/**
 * Attach the echoed id to a reply.
 *
 * A `callId` the payload itself carries wins, so a future reply that knows its own
 * id is not clobbered by the echo. Nothing today does, which is why this is stated
 * rather than left to argument order.
 */
export function withCallId(payload, frame) {
  const id = callIdOf(frame);
  if (id === null) return payload;
  const base = payload && typeof payload === 'object' ? payload : {};
  if (typeof base.callId === 'string' && base.callId.length > 0) return base;
  return { ...base, callId: id };
}
