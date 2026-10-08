/**
 * The fallback addresses a phone walks when the relay is down.
 *
 * The failure this guards against is a phone with exactly one address in it: if
 * that address is the relay and the relay is unreachable, the app is dead even
 * though the two devices are on the same Wi-Fi. The second failure is subtler -
 * a list that puts loopback first looks fine in a test and can never work for a
 * real phone.
 *
 * Free: no socket is opened, nothing is dialled.
 *
 *   node tools/fallback-addresses-test.js
 */
import { connectionCandidates, fallbackCount, describeCandidates } from '../src/fallback.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const LAN = ['ws://192.168.248.180:7420', 'ws://100.90.192.5:7420'];

// ---- the order that matters --------------------------------------------------

{
  const list = connectionCandidates({ primary: 'wss://term.example/ws', lanUrls: LAN, port: 7420 });
  check('the pairing address is tried first', list[0] === 'wss://term.example/ws', list[0]);
  check('then the machine\'s own addresses, in the order they were ranked',
    list[1] === LAN[0] && list[2] === LAN[1], list.slice(1).join(' '));
  check('and loopback is last, because it can only work on this very machine',
    list[list.length - 1] === 'ws://127.0.0.1:7420', list[list.length - 1]);
  check('the count of fallbacks is honest', fallbackCount(list) === 3, String(fallbackCount(list)));
}

{
  // The phone IS this machine (the sandbox case): loopback is the only one that works.
  const list = connectionCandidates({ primary: null, lanUrls: [], port: 7431 });
  check('with nothing else, loopback is still offered', list.length === 1 && list[0] === 'ws://127.0.0.1:7431', list.join(' '));
}

{
  const list = connectionCandidates({ primary: LAN[0], lanUrls: LAN, port: 7420 });
  check('an address that is both primary and a LAN candidate is not listed twice',
    list.filter((u) => u === LAN[0]).length === 1, list.join(' '));
}

// ---- inputs that must not produce a broken candidate -------------------------

{
  const list = connectionCandidates({ primary: 'not a url', lanUrls: ['also not'], port: 7420 });
  check('junk is dropped rather than dialled', list.length === 1 && list[0].startsWith('ws://127.0.0.1'), list.join(' '));
}

{
  const list = connectionCandidates({ primary: 'wss://a.example', lanUrls: [null, 42, 'ws://ok:1'], port: 7420 });
  check('non-strings in the LAN list are skipped', list.length === 3 && list[1] === 'ws://ok:1', list.join(' '));
}

{
  const list = connectionCandidates({ primary: 'wss://a.example', lanUrls: [], port: 7420 });
  check('loopback is not added twice when it is already there',
    list.filter((u) => u.includes('127.0.0.1')).length === 1, list.join(' '));
}

check('no input at all still yields loopback',
  connectionCandidates({}).length === 1, connectionCandidates({}).join(' '));

// ---- what the banner says ----------------------------------------------------

{
  const one = describeCandidates(['wss://only.example']);
  check('a single address is described as the only way in, not as a healthy setup',
    one.includes('唯一入口'), one);
  const many = describeCandidates(['wss://a', 'ws://b', 'ws://c']);
  check('with fallbacks it says how many', many.includes('2'), many);
  check('an empty list says so', describeCandidates([]).length > 0, describeCandidates([]));
}

// ---- the same order the product asked for, end to end ------------------------

{
  // A LAN address is ranked above Tailscale by `lanAddresses()`; this asserts the
  // candidate list does not re-sort it, which would quietly undo that ranking.
  const list = connectionCandidates({
    primary: 'wss://relay.example',
    lanUrls: ['ws://192.168.1.5:7420', 'ws://100.64.0.9:7420', 'ws://172.30.240.1:7420'],
    port: 7420,
  });
  check('home range, then Tailscale, then the virtual switch',
    list.slice(1, 4).join(' ') === 'ws://192.168.1.5:7420 ws://100.64.0.9:7420 ws://172.30.240.1:7420',
    list.join(' '));
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
