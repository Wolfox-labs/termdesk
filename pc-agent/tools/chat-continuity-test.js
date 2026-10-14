import assert from 'node:assert/strict';
import test from 'node:test';
import { ChatContinuity } from '../src/chat/continuity.js';
import { ChatManager } from '../src/chat.js';
import { createFrameHandler } from '../src/handlers.js';

const phone = { deviceId: 'phone', deviceName: 'OPPO' };
const other = { deviceId: 'other', deviceName: 'Other phone' };
function fixture() {
  let now = 1000;
  const leases = new ChatContinuity({ now: () => now, leaseMs: 100 });
  leases.claim('c', phone);
  return { leases, expire: () => { now += 101; } };
}

test('the original device can stop or approve a running turn after its lease expires', () => {
  const { leases, expire } = fixture();
  expire();
  assert.equal(leases.checkWrite('c', phone, { running: true }).ok, true);
  assert.equal(leases.owner('c').deviceId, phone.deviceId);
});
test('reading an expired lease does not lose the original controller identity', () => {
  const { leases, expire } = fixture();
  expire();
  assert.equal(leases.describe('c', other).mode, 'available');
  assert.equal(leases.checkWrite('c', phone, { running: true }).ok, true);
});
test('another device cannot force ownership of an expired running turn', () => {
  const { leases, expire } = fixture();
  expire();
  assert.equal(leases.claim('c', other, { running: true, force: true }).ok, false);
});
test('an expired idle conversation can be claimed by a different device', () => {
  const { leases, expire } = fixture();
  expire();
  assert.equal(leases.claim('c', other).ok, true);
  assert.equal(leases.checkWrite('c', phone, { running: true }).ok, false);
});
test('a returning owner renews an expired lease without an observer taking it', () => {
  const { leases, expire } = fixture();
  expire();
  assert.equal(leases.touch('c', other).owner, null);
  assert.equal(leases.touch('c', phone).mode, 'owner');
});
test('an explicit release does not retain recovery ownership', () => {
  const { leases, expire } = fixture();
  expire();
  leases.release('c', phone);
  assert.equal(leases.claim('c', phone, { running: true }).ok, false);
});
test('forced idle takeover prevents the previous device from writing', () => {
  const { leases } = fixture();
  assert.equal(leases.claim('c', other, { force: true }).ok, true);
  assert.equal(leases.checkWrite('c', phone).ok, false);
});
test('reconnect sync returns an authoritative snapshot even with a current cursor', async () => {
  const chats = new ChatManager();
  const result = chats.create({ engine: 'dsh', cwd: process.cwd() });
  const chat = chats.get(result.chat.id);
  const frames = [];
  const leases = new ChatContinuity();
  leases.claim(chat.id, phone);
  const handler = createFrameHandler({ chats, continuity: leases, identity: phone, send: (type, payload) => frames.push({ type, ...payload }), socket: {}, terminals: {}, shellEnabled: false });
  await handler({ type: 'chat.sync', chatId: chat.id, afterSeq: chat.seq });
  const snapshot = frames.find((frame) => frame.type === 'chat');
  assert.equal(snapshot.eventSync.mode, 'full');
  assert.equal(snapshot.events.length, chat.events.length);
  assert.equal(snapshot.ownership.mode, 'owner');
});
test('a cursor outside retained history falls back to a full snapshot', () => {
  const chats = new ChatManager();
  const result = chats.create({ engine: 'dsh', cwd: process.cwd() });
  const chat = chats.get(result.chat.id);
  const snapshot = chat.detail(chat.seq + 100);
  assert.equal(snapshot.eventSync.mode, 'full');
  assert.equal(snapshot.events.length, chat.events.length);
});
