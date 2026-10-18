import net from 'node:net';

/** Only an explicitly trusted reverse proxy may supply a replacement client IP. */
export function peerAddress(req, trustedProxies = []) {
  const peer = String(req.socket.remoteAddress ?? 'unknown');
  const claimed = req.headers['x-real-ip'];
  if (Array.isArray(trustedProxies) && trustedProxies.includes(peer)
      && typeof claimed === 'string' && net.isIP(claimed)) return claimed;
  return peer;
}
