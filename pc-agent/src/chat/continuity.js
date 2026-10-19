/**
 * Cross-device ownership for one live conversation.
 *
 * A chat may be observed by many clients, but only the device holding its lease
 * may mutate it. The lease survives a short reconnect window for the same
 * stable device id, while explicit takeover is allowed only when the kernel is
 * idle. This module is independent from sockets and Chat so it can be tested
 * without starting an agent.
 */

export const DEFAULT_LEASE_MS = 5 * 60 * 1000;

function clean(value, fallback) {
  const text = typeof value === 'string' ? value.trim() : '';
  return text || fallback;
}

export function normalizeIdentity(identity = {}, fallback = {}) {
  const deviceId = clean(identity.deviceId, clean(fallback.deviceId, 'legacy-device'));
  const deviceName = clean(identity.deviceName, clean(fallback.deviceName, deviceId));
  const clientKind = clean(identity.clientKind, clean(fallback.clientKind, 'unknown'));
  return { deviceId, deviceName, clientKind };
}

function publicOwner(owner) {
  if (!owner) return null;
  return {
    deviceId: owner.deviceId,
    deviceName: owner.deviceName,
    clientKind: owner.clientKind,
    acquiredAt: owner.acquiredAt,
    lastSeenAt: owner.lastSeenAt,
    leaseExpiresAt: owner.leaseExpiresAt,
  };
}

export class ChatContinuity {
  constructor({ leaseMs = DEFAULT_LEASE_MS, now = () => Date.now() } = {}) {
    this.leaseMs = leaseMs;
    this.now = now;
    this.records = new Map();
  }

  _record(chatId) {
    let record = this.records.get(chatId);
    if (!record) {
      record = { owner: null, lastOwner: null, waiters: [] };
      this.records.set(chatId, record);
    }
    return record;
  }

  _clearExpired(record, at = this.now()) {
    if (record.owner && record.owner.leaseExpiresAt <= at) {
      record.lastOwner = record.owner;
      record.owner = null;
    }
  }

  owner(chatId) {
    const record = this.records.get(chatId);
    if (!record) return null;
    this._clearExpired(record);
    return publicOwner(record.owner);
  }

  describe(chatId, identity = {}) {
    const record = this._record(chatId);
    this._clearExpired(record);
    const me = normalizeIdentity(identity);
    const owner = publicOwner(record.owner);
    const isOwner = Boolean(record.owner && record.owner.deviceId === me.deviceId);
    return {
      owner,
      mode: isOwner ? 'owner' : owner ? 'observer' : 'available',
      queued: record.waiters.some((w) => w.deviceId === me.deviceId),
      leaseMs: owner ? Math.max(0, owner.leaseExpiresAt - this.now()) : 0,
    };
  }

  claim(chatId, identity = {}, { force = false, running = false } = {}) {
    const record = this._record(chatId);
    const now = this.now();
    this._clearExpired(record, now);
    const me = normalizeIdentity(identity);
    if (!record.owner) {
      // A long turn must not lock out the device which started it. Expiry
      // permits takeover when idle, but never gives another device a running turn.
      if (running && record.lastOwner?.deviceId !== me.deviceId) {
        return { ok: false, code: 'session_running', mode: 'observer', owner: null, message: '当前会话正在运行，暂时无法确认控制设备，请等待本轮结束后再接管' };
      }
      record.owner = { ...me, acquiredAt: now, lastSeenAt: now, leaseExpiresAt: now + this.leaseMs };
      record.lastOwner = null;
      record.waiters = record.waiters.filter((w) => w.deviceId !== me.deviceId);
      return { ok: true, code: 'claimed', mode: 'owner', owner: publicOwner(record.owner) };
    }
    if (record.owner.deviceId === me.deviceId) {
      record.owner = { ...record.owner, ...me, lastSeenAt: now, leaseExpiresAt: now + this.leaseMs };
      return { ok: true, code: 'renewed', mode: 'owner', owner: publicOwner(record.owner) };
    }
    if (force && !running) {
      const previousOwner = publicOwner(record.owner);
      record.owner = { ...me, acquiredAt: now, lastSeenAt: now, leaseExpiresAt: now + this.leaseMs };
      record.lastOwner = null;
      record.waiters = record.waiters.filter((w) => w.deviceId !== me.deviceId);
      return { ok: true, code: 'taken_over', mode: 'owner', owner: publicOwner(record.owner), previousOwner };
    }
    if (!record.waiters.some((w) => w.deviceId === me.deviceId)) record.waiters.push(me);
    return {
      ok: false,
      code: running ? 'session_running' : 'session_busy',
      mode: 'observer',
      owner: publicOwner(record.owner),
      message: running
        ? `当前会话正在由${record.owner.deviceName}控制并生成回复，生成结束后再接管`
        : `当前会话由${record.owner.deviceName}控制，请先申请接管`,
    };
  }

  release(chatId, identity = {}) {
    const record = this.records.get(chatId);
    if (!record) return { ok: true, code: 'released', owner: null };
    this._clearExpired(record);
    const me = normalizeIdentity(identity);
    if (!record.owner) {
      if (record.lastOwner?.deviceId === me.deviceId) record.lastOwner = null;
      return { ok: true, code: 'released', owner: null };
    }
    if (record.owner.deviceId !== me.deviceId) {
      return { ok: false, code: 'not_owner', owner: publicOwner(record.owner), message: '只有当前控制设备可以释放会话' };
    }
    record.owner = null;
    record.lastOwner = null;
    return { ok: true, code: 'released', owner: null };
  }

  checkWrite(chatId, identity = {}, { running = false } = {}) {
    const result = this.claim(chatId, identity, { running });
    return result.ok ? result : { ...result, message: result.message ?? '当前会话由另一台设备控制，请先接管后再操作' };
  }

  touch(chatId, identity = {}) {
    const record = this.records.get(chatId);
    if (!record) return this.describe(chatId, identity);
    this._clearExpired(record);
    const me = normalizeIdentity(identity);
    if (!record.owner && record.lastOwner?.deviceId === me.deviceId) {
      this.claim(chatId, me, { running: true });
    }
    if (record.owner?.deviceId === me.deviceId) {
      const now = this.now();
      record.owner.lastSeenAt = now;
      record.owner.leaseExpiresAt = now + this.leaseMs;
    }
    return this.describe(chatId, identity);
  }

  remove(chatId) {
    this.records.delete(chatId);
  }
}

export function ownerForResponse(chatId, continuity, identity) {
  return continuity?.describe(chatId, identity) ?? { owner: null, mode: 'available', queued: false, leaseMs: 0 };
}
