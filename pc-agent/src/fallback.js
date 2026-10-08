/**
 * The addresses a phone should try, in order.
 *
 * Why this exists: the relay became the single entry point, which is a single
 * point of failure. When it is down the phone has no address left, even though
 * the two devices may be sitting on the same Wi-Fi — or on the same Tailscale
 * network, which works from anywhere and does not involve Cloudflare at all.
 *
 * So the pairing payload now carries the fallbacks alongside the primary address,
 * and the phone walks the list. The order is the product decision:
 *
 *   1. whatever address the pairing chose (the relay, normally);
 *   2. the machine's own LAN/Tailscale addresses, best first — the ordering comes
 *      from `lanAddresses()` in server.js, which already puts home/office ranges
 *      ahead of CGNAT/Tailscale ahead of virtual switches;
 *   3. loopback last, which only helps when the phone IS this machine (the
 *      sandbox case) but costs nothing to include.
 *
 * Deliberately not "try everything at once": a phone on a train would then open
 * sockets to addresses that cannot possibly answer, and the first address that
 * works is almost always the right one.
 *
 * Pure functions, so `tools/fallback-addresses-test.js` can pin the order and the
 * de-duplication without opening a socket.
 */

/** Hosts that cannot be reached from another device, but are right for loopback. */
function isLoopback(url) {
  return /^wss?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:|\/|$)/i.test(url);
}

/** A websocket URL is only a candidate if it is one at all. */
function usable(url) {
  return typeof url === 'string' && /^wss?:\/\/[^\s]+$/i.test(url);
}

/**
 * Build the ordered candidate list.
 *
 * @param {object} spec
 * @param {string|null} spec.primary the address pairing chose (may be null)
 * @param {string[]} spec.lanUrls `ws://<addr>:<port>` for this machine, best first
 * @param {number} spec.port
 * @returns {string[]} candidates, primary first, de-duplicated, loopback last
 */
export function connectionCandidates({ primary = null, lanUrls = [], port = 7420 } = {}) {
  const out = [];
  const push = (url) => {
    if (!usable(url)) return;
    if (!out.includes(url)) out.push(url);
  };

  push(primary);
  // The LAN list may already contain the primary; `push` drops the repeat.
  for (const url of lanUrls ?? []) push(url);
  // Loopback is worth carrying for the sandbox case, where the "phone" and the
  // agent are the same device. It goes last: for a real phone it can never work.
  const loopback = `ws://127.0.0.1:${port}`;
  if (!out.some(isLoopback)) push(loopback);
  return out;
}

/** How many candidates are not the primary, i.e. how many fallbacks exist. */
export function fallbackCount(candidates) {
  return Math.max(0, (candidates ?? []).length - 1);
}

/**
 * One line for the startup banner and the pairing page.
 *
 * Stated even when there is nothing to fall back to: "the relay is the only way
 * in" is information the person needs before the relay goes down, not after.
 */
export function describeCandidates(candidates) {
  const list = candidates ?? [];
  if (list.length === 0) return '没有可用地址';
  if (list.length === 1) return `${list[0]}（唯一入口：中转不通就连不上）`;
  return `${list[0]}（另有 ${list.length - 1} 个备用地址：中转不通时按序回退）`;
}
