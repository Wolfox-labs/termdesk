import test from 'node:test';
import assert from 'node:assert/strict';
import { peerAddress } from '../src/peer-address.js';

test('an internet caller cannot evade throttling by forging proxy headers', () => {
  const req = { socket: { remoteAddress: '203.0.113.10' }, headers: { 'x-real-ip': '198.51.100.20', 'cf-connecting-ip': '198.51.100.30' } };
  assert.equal(peerAddress(req), '203.0.113.10');
  assert.equal(peerAddress(req, ['127.0.0.1']), '203.0.113.10');
});

test('a trusted local TLS proxy can preserve the actual client throttle bucket', () => {
  const req = { socket: { remoteAddress: '127.0.0.1' }, headers: { 'x-real-ip': '198.51.100.20' } };
  assert.equal(peerAddress(req, ['127.0.0.1']), '198.51.100.20');
  for (const invalid of ['', '198.51.100.20, 203.0.113.10', ['198.51.100.20'], 'forged']) {
    req.headers['x-real-ip'] = invalid;
    assert.equal(peerAddress(req, ['127.0.0.1']), '127.0.0.1');
  }
});
