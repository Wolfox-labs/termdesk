/**
 * The phones paired with this computer.
 *
 * The point of this file is the many-to-many shape: several people, each with
 * their own computer and phone, and several phones allowed on one computer — so
 * pairing has to mint a per-device credential instead of handing out the one
 * secret every client would then share.
 *
 * Costs nothing: no kernel, no model, no network. It writes to a temp file, never
 * to the user's real registry.
 *
 *   node tools/devices-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DeviceRegistry, LEGACY_DEVICE_ID } from '../src/devices.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-devices-'));
const file = path.join(dir, 'devices.json');
const MASTER = 'master-token-'.padEnd(40, 'x');

const registry = new DeviceRegistry({ masterToken: MASTER, file });

// ---- the machine's own token still works, and shows up as a device ----------

check('the machine token authenticates', registry.verify(MASTER)?.id === LEGACY_DEVICE_ID);
check('and it is listed as a device', registry.list().some((d) => d.id === LEGACY_DEVICE_ID && d.legacy));
check('a wrong secret authenticates nothing', registry.verify('not-the-token') === null);
check('an empty secret authenticates nothing', registry.verify('') === null);

// ---- pairing mints a device -------------------------------------------------

const alice = registry.register({ name: 'Alice 的手机' });
const bob = registry.register({ name: 'Bob 的手机' });
check('each pairing gets its own id', alice.id !== bob.id, `${alice.id} / ${bob.id}`);
check('each pairing gets its own secret', alice.secret !== bob.secret && alice.secret.length >= 32);
check('a secret is never the machine token', alice.secret !== MASTER && bob.secret !== MASTER);
check('the device list names them', registry.list().some((d) => d.name === 'Alice 的手机'));

// ---- they are independent --------------------------------------------------

check('device A authenticates as A', registry.verify(alice.secret)?.id === alice.id);
check('device B authenticates as B', registry.verify(bob.secret)?.id === bob.id);
check(
  'authenticating does not disturb the other device',
  registry.list().filter((d) => d.revoked).length === 0,
);

registry.revoke(bob.id);
check('a revoked device stops authenticating', registry.verify(bob.secret) === null);
check('and is reported as revoked rather than unknown', registry.wasRevoked(bob.secret));
check('revoking one leaves the other working', registry.verify(alice.secret)?.id === alice.id);
check('revoking one leaves the machine token working', registry.verify(MASTER)?.id === LEGACY_DEVICE_ID);
check('the list says which one is revoked', registry.list().find((d) => d.id === bob.id)?.revoked === true);

registry.revoke(alice.id);
check('revoking the second one also works', registry.verify(alice.secret) === null);

// ---- renaming and forgetting ------------------------------------------------

const carol = registry.register({});
check('an unnamed pairing still gets a readable name', registry.list().some((d) => d.id === carol.id && d.name === '未命名手机'));
registry.rename(carol.id, 'Carol 的平板');
check('renaming shows up in the list', registry.list().find((d) => d.id === carol.id)?.name === 'Carol 的平板');
check('removing forgets the record', registry.remove(carol.id) === true);
check('and it is gone from the list', !registry.list().some((d) => d.id === carol.id));
check('removing something unknown is a no-op', registry.remove('d-nope') === false);

// ---- last seen is recorded, so the list can say who is actually connecting ---

const dave = registry.register({ name: 'Dave 的手机' });
registry.verify(dave.secret, { address: '10.0.0.9' });
const seen = registry.list().find((d) => d.id === dave.id);
check('last seen time is recorded', Boolean(seen?.lastSeenAt));
check('last address is recorded', seen?.lastAddress === '10.0.0.9');

// ---- it survives a restart -------------------------------------------------

const reloaded = new DeviceRegistry({ masterToken: MASTER, file });
check('the registry reloads from disk', reloaded.list().length === registry.list().length);
check('a device still authenticates after a restart', reloaded.verify(dave.secret)?.id === dave.id);
check('a revoked device is still revoked after a restart', reloaded.verify(bob.secret) === null);

// ---- the file is not world readable ----------------------------------------

if (process.platform === 'win32') {
  // Windows has no POSIX mode bits: `chmod 0600` there sets the read-only flag
  // rather than permissions, and `statSync().mode` reports 0666 whatever the file
  // was created with. Asserting 0600 would fail for a file that IS private to the
  // user (it lives in the user's own profile), so the check is skipped with the
  // reason stated rather than weakened for everyone.
  console.log('SKIP  the registry file is private to the user (Windows has no POSIX modes)');
} else {
  try {
    const mode = fs.statSync(file).mode & 0o777;
    check('the registry file is private to the user', mode === 0o600, `mode=${mode.toString(8)}`);
  } catch (err) {
    check('the registry file is private to the user', false, String(err?.message ?? err));
  }
}

fs.rmSync(dir, { recursive: true, force: true });

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
