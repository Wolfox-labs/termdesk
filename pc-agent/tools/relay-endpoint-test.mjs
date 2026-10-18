import test from 'node:test';
import assert from 'node:assert/strict';
import { relayEndpoint } from '../src/relay-client.js';

test('node control and transfer channels remain inside a relay path prefix', () => {
  for (const base of ['wss://relay.example:8443/termdesk', 'wss://relay.example:8443/termdesk/']) {
    assert.equal(relayEndpoint(base, '/agent').href, 'wss://relay.example:8443/termdesk/agent');
    assert.equal(relayEndpoint(base, '/transfer').href, 'wss://relay.example:8443/termdesk/transfer');
  }
  assert.equal(relayEndpoint('wss://relay.example', '/agent').href, 'wss://relay.example/agent');
});
