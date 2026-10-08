/**
 * The phones this computer has been paired with.
 *
 * Why this exists: TermDesk is many-to-many. Several people use their own
 * computer with their own phone, and one computer can be used by several phones
 * at once — so "the token" cannot be one secret shared by every client of this
 * machine. That model made a lost phone into "change the token and re-pair
 * everything", gave no way to see who is connected, and no way to revoke one
 * phone without disturbing the others.
 *
 * So a pairing mints a device: its own random secret, its own name, its own
 * record. The agent can list them, and revoke exactly one. The original token
 * file still works and is treated as one legacy device, so a phone paired with
 * an older build keeps connecting.
 *
 * What this is NOT (yet): public-key identity. A per-device random secret is the
 * smallest change that makes the relationship manageable — separate credentials,
 * separate revocation, a visible list — and it is verifiable without any new
 * cryptography on the phone. Upgrading to device key pairs later is a change to
 * this file's `verify`, not to the shape of the data.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { tokenMatches } from './auth.js';

const DIR = path.join(os.homedir(), '.termdesk');
const FILE = path.join(DIR, 'devices.json');

/** The id used for the machine's original token, so old pairings keep a name. */
export const LEGACY_DEVICE_ID = 'legacy';

function read(file = FILE) {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    return {
      devices: Array.isArray(parsed?.devices) ? parsed.devices : [],
      legacyLastSeenAt: parsed?.legacyLastSeenAt ?? null,
      legacyLastAddress: parsed?.legacyLastAddress ?? null,
    };
  } catch {
    return { devices: [] };
  }
}

function write(state, file = FILE) {
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
}

/** A short, human-readable id: enough to tell two phones apart in a list. */
function newId() {
  return `d-${crypto.randomBytes(4).toString('hex')}`;
}

/**
 * The registry, loaded once per process.
 *
 * `masterToken` is the machine's original token: it is accepted as the legacy
 * device, and it is what authorises minting a new device (the pairing page runs
 * on loopback and already requires the token to be read from disk, so this is
 * the same trust level as before).
 */
export class DeviceRegistry {
  constructor({ masterToken, file = FILE } = {}) {
    this.masterToken = masterToken ?? null;
    this.file = file;
    this.state = read(this.file);
  }

  /** Every device, newest last, with the legacy token shown as one of them. */
  list() {
    const devices = this.state.devices.map((d) => ({
      id: d.id,
      name: d.name,
      createdAt: d.createdAt,
      lastSeenAt: d.lastSeenAt ?? null,
      lastAddress: d.lastAddress ?? null,
      revoked: Boolean(d.revoked),
    }));
    if (this.masterToken) {
      devices.unshift({
        id: LEGACY_DEVICE_ID,
        name: '（旧版配对：这台电脑的令牌）',
        createdAt: null,
        lastSeenAt: this.state.legacyLastSeenAt ?? null,
        lastAddress: this.state.legacyLastAddress ?? null,
        revoked: false,
        legacy: true,
      });
    }
    return devices;
  }

  /**
   * Mint a device for a phone that just paired.
   *
   * Returns the secret exactly once: the QR code carries it, the agent stores a
   * hash-free copy because it has to compare it later — the file lives in the
   * user's own home with 0600, the same place the master token already lives.
   */
  register({ name = null, note = null } = {}) {
    const device = {
      id: newId(),
      name: name && String(name).trim() ? String(name).trim() : '未命名手机',
      secret: crypto.randomBytes(32).toString('base64url'),
      createdAt: new Date().toISOString(),
      lastSeenAt: null,
      lastAddress: null,
      note: note ?? null,
      revoked: false,
    };
    this.state.devices.push(device);
    write(this.state, this.file);
    return { id: device.id, name: device.name, secret: device.secret, createdAt: device.createdAt };
  }

  /**
   * Which device (if any) a presented secret belongs to.
   *
   * Constant-time comparisons, one per device: the list is a handful of entries,
   * and timing that reveals *which* device matched would be a new leak.
   */
  verify(secret, { address = null } = {}) {
    if (typeof secret !== 'string' || secret.length === 0) return null;
    for (const device of this.state.devices) {
      if (device.revoked) continue;
      if (!tokenMatches(device.secret, secret)) continue;
      device.lastSeenAt = new Date().toISOString();
      device.lastAddress = address ?? device.lastAddress ?? null;
      write(this.state, this.file);
      return { id: device.id, name: device.name, legacy: false };
    }
    if (this.masterToken && tokenMatches(this.masterToken, secret)) {
      this.state.legacyLastSeenAt = new Date().toISOString();
      this.state.legacyLastAddress = address ?? this.state.legacyLastAddress ?? null;
      write(this.state, this.file);
      return { id: LEGACY_DEVICE_ID, name: '（旧版配对）', legacy: true };
    }
    return null;
  }

  /** Was this secret a device that has since been revoked? (For the UI's sake.) */
  wasRevoked(secret) {
    if (typeof secret !== 'string') return false;
    return this.state.devices.some((d) => d.revoked && tokenMatches(d.secret, secret));
  }

  rename(id, name) {
    const device = this.state.devices.find((d) => d.id === id);
    if (!device) return null;
    device.name = String(name ?? '').trim() || device.name;
    write(this.state, this.file);
    return { id: device.id, name: device.name };
  }

  /** Revoke one phone. Everything else keeps working. */
  revoke(id) {
    if (id === LEGACY_DEVICE_ID) return null;
    const device = this.state.devices.find((d) => d.id === id);
    if (!device) return null;
    device.revoked = true;
    device.revokedAt = new Date().toISOString();
    write(this.state, this.file);
    return { id: device.id, name: device.name, revoked: true };
  }

  /** Forget a device entirely (removes the record, not just access). */
  remove(id) {
    const before = this.state.devices.length;
    this.state.devices = this.state.devices.filter((d) => d.id !== id);
    write(this.state, this.file);
    return this.state.devices.length !== before;
  }
}
