import crypto from 'node:crypto';
import fs from 'node:fs';

export const digest = value => crypto.createHash('sha256').update(value).digest('hex');
export const secret = () => crypto.randomBytes(32).toString('base64url');
function matches(hash, value) {
  if (typeof hash !== 'string' || typeof value !== 'string' || value.length > 256) return false;
  const a = Buffer.from(hash, 'hex'), b = Buffer.from(digest(value), 'hex');
  return a.length === 32 && crypto.timingSafeEqual(a, b);
}

/** A label ends up in a list on someone's screen: one line, bounded. */
function cleanLabel(value) {
  if (typeof value !== 'string') return '';
  return value.replace(/[\r\n\t]+/g, ' ').trim().slice(0, 40);
}

/**
 * Pairing codes are short enough to read off a screen and type by hand when the
 * camera is not an option, so the alphabet drops the look-alike characters
 * (no I, L, O, U) and comparison normalises what a human types back: `O` is a
 * `0`, `I` and `L` are a `1`. Twelve characters of this alphabet is ~59 bits,
 * which is far beyond guessing inside the ten minutes a code lives.
 */
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';
export const PAIRING_CODE_LENGTH = 12;
export function pairingCode(length = PAIRING_CODE_LENGTH) {
  let out = '';
  for (let i = 0; i < length; i += 1) out += ALPHABET[crypto.randomInt(ALPHABET.length)];
  return out;
}
export function normalizeCode(value) {
  if (typeof value !== 'string') return '';
  return value.replace(/[^0-9A-Za-z]/g, '').toUpperCase().replace(/O/g, '0').replace(/[IL]/g, '1');
}
/** Grouped for reading aloud and typing; the code itself has no separators. */
export const formatCode = code => String(code).replace(/(.{4})(?=.)/g, '$1-');

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
  /**
   * Consume a pairing code and mint the device it stands for.
   *
   * The code arrives either straight from a QR scan (canonical form) or typed by
   * a human (any spacing, and `O`/`I`/`L` for `0`/`1`), so both spellings are
   * tried. `name` is what the phone calls itself; it only ever reaches the node
   * that owns the pairing.
   */
  pair(code, { name = null } = {}) {
    const candidates = [code, normalizeCode(code)].filter((v, i, a) => typeof v === 'string' && v.length > 0 && a.indexOf(v) === i);
    let index = -1, used = null;
    for (const candidate of candidates) {
      index = this.config.pairings.findIndex(p => p.expiresAt > Date.now() && matches(p.codeHash, candidate));
      if (index >= 0) { used = candidate; break; }
    }
    if (index < 0) return null;
    const pairing = this.config.pairings[index], token = secret();
    const now = Date.now();
    const device = {
      id: crypto.randomUUID(),
      nodeId: pairing.nodeId,
      label: cleanLabel(name) || pairing.label || 'Android',
      keyHash: digest(token),
      createdAt: now,
      lastSeenAt: new Date(now).toISOString(),
    };
    this.config.pairings.splice(index, 1);
    this.config.devices.push(device);
    try { this.save(); } catch (err) {
      this.config.devices.pop(); this.config.pairings.splice(index, 0, pairing); throw err;
    }
    return { device, token, code: used };
  }

  /**
   * Mint a one-time code for a node to hand to a phone.
   *
   * The node asks for this over its own authenticated channel, so a computer can
   * always add a phone on its own without anybody logging into the relay. Only
   * the hash is written down; the code exists in the reply and on the screen.
   */
  createPairing({ nodeId, label = 'Android', ttlMs = 10 * 60_000, max = 5 } = {}) {
    if (!this.config.nodes.some(n => n.id === nodeId)) return null;
    const now = Date.now();
    // A node picks the window, within reason: too short and nobody can walk to the
    // other device, too long and a leaked code keeps working.
    const ttl = Math.min(Math.max(Number(ttlMs) || 10 * 60_000, 60_000), 60 * 60_000);
    const cap = Math.min(Math.max(Number(max) || 5, 1), 20);
    this.config.pairings = this.config.pairings.filter(p => p.expiresAt > now);
    if (this.config.pairings.filter(p => p.nodeId === nodeId).length >= cap) return null;
    const code = pairingCode();
    const pairing = { nodeId, label: cleanLabel(label) || 'Android', codeHash: digest(code), createdAt: now, expiresAt: now + ttl };
    this.config.pairings.push(pairing);
    try { this.save(); } catch (err) { this.config.pairings.pop(); throw err; }
    return { code, formatted: formatCode(code), expiresAt: pairing.expiresAt, label: pairing.label };
  }

  /** The phones a node has paired. Hashes never leave this file. */
  devicesOf(nodeId) {
    return this.config.devices
      .filter(d => d.nodeId === nodeId)
      .map(d => ({ id: d.id, label: d.label, createdAt: d.createdAt ?? null, lastSeenAt: d.lastSeenAt ?? null, revoked: Boolean(d.revoked) }));
  }

  /** Remember when a device was last seen, without a disk write per reconnect. */
  touchDevice(deviceId) {
    const device = this.config.devices.find(d => d.id === deviceId);
    if (!device) return;
    const now = Date.now(), previous = Date.parse(device.lastSeenAt || '') || 0;
    device.lastSeenAt = new Date(now).toISOString();
    if (now - previous > 60_000) { try { this.save(); } catch { /* a stale timestamp is not worth failing a connection over */ } }
  }

  /** Revoke one phone of this node. Returns false when there is nothing to revoke. */
  revokeDevice(nodeId, deviceId) {
    const device = this.config.devices.find(d => d.id === deviceId && d.nodeId === nodeId);
    if (!device || device.revoked) return false;
    device.revoked = true;
    device.revokedAt = new Date().toISOString();
    this.save();
    return true;
  }

  /** Forget a device entirely: used when a phone unbinds itself. */
  removeDevice(nodeId, deviceId) {
    const index = this.config.devices.findIndex(d => d.id === deviceId && d.nodeId === nodeId);
    if (index < 0) return false;
    const [device] = this.config.devices.splice(index, 1);
    try { this.save(); } catch (err) { this.config.devices.splice(index, 0, device); throw err; }
    return true;
  }
  save() {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.config, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}
