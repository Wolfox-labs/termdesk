import crypto from 'node:crypto';
import fs from 'node:fs';

export const digest = value => crypto.createHash('sha256').update(value).digest('hex');
export const secret = () => crypto.randomBytes(32).toString('base64url');
function matches(hash, value) {
  if (typeof hash !== 'string' || typeof value !== 'string' || value.length > 256) return false;
  const a = Buffer.from(hash, 'hex'), b = Buffer.from(digest(value), 'hex');
  return a.length === 32 && crypto.timingSafeEqual(a, b);
}

/** Disk contains hashes, not usable node/device credentials. Pairings are consumed atomically. */
export class CredentialStore {
  constructor(config, file = null) {
    if (config.version !== 1 || !Array.isArray(config.nodes)) throw new Error('Invalid relay configuration');
    this.config = config;
    this.file = file;
    config.devices ??= [];
    config.pairings ??= [];
  }
  node(id, token) { return this.config.nodes.find(n => n.id === id && matches(n.keyHash, token)); }
  device(token) { return this.config.devices.find(d => !d.revoked && matches(d.keyHash, token)); }
  pair(code) {
    const index = this.config.pairings.findIndex(p => p.expiresAt > Date.now() && matches(p.codeHash, code));
    if (index < 0) return null;
    const pairing = this.config.pairings[index], token = secret();
    const device = { id: crypto.randomUUID(), nodeId: pairing.nodeId, label: pairing.label || 'Android', keyHash: digest(token), createdAt: Date.now() };
    this.config.pairings.splice(index, 1);
    this.config.devices.push(device);
    try { this.save(); } catch (err) {
      this.config.devices.pop(); this.config.pairings.splice(index, 0, pairing); throw err;
    }
    return { device, token };
  }
  save() {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.config, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}
