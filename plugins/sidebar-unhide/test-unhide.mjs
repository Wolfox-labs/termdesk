/**
 * Static checks for dsh-sidebar-unhide that need no running DSH.
 *
 *   node plugins/sidebar-unhide/test-unhide.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (rel) => fs.readFileSync(path.join(here, rel), 'utf8');

const failures = [];
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(name);
};

const client = read('lib/client.js');
const index = read('lib/index.js');
const patch = read('cordis.patch.yml');
const pkg = JSON.parse(read('package.json'));

// --- package manifest ---
check('package name is dsh-sidebar-unhide', pkg.name === 'dsh-sidebar-unhide');
check('declares client entry', pkg.exports?.['./client'] === './lib/client.js');
check('declares bundle patch', pkg.dsh?.bundle?.patch === 'cordis.patch.yml');
check('client platform is web', pkg.dsh?.client?.platform === 'web');

// --- patch file ---
check('patch inserts sidebar-unhide', /id:\s*sidebar-unhide/.test(patch));
check('patch names dsh-sidebar-unhide', /name:\s*dsh-sidebar-unhide/.test(patch));

// --- server stub is inert ---
check('server apply is a no-op', /export function apply\(_ctx\)/.test(index));

// --- client module shape (must match dsh ModuleLoader contract) ---
check('client uses ModuleLoader', /window\.__ModuleLoader__\.load/.test(client));
check('client exports apply', /exports\.apply\s*=\s*apply/.test(client));
check('client exports inject', /exports\.inject\s*=\s*inject/.test(client));

// --- Tauri detection covers the documented signals ---
check('detects __TAURI__', /__TAURI__/.test(client));
check('detects __TAURI_INTERNALS__', /__TAURI_INTERNALS__/.test(client));
check('detects __TAURI_IPC__', /__TAURI_IPC__/.test(client));
check('detects framed parent', /window\.parent\s*&&\s*window\.parent\s*!==\s*window/.test(client));

// --- CSS restores the button ---
check(
  'targets zh collapse label',
  /收起侧边栏/.test(client),
);
check(
  'targets en collapse label',
  /Collapse sidebar/.test(client),
);
check(
  'uses inline-flex !important',
  /display:\s*inline-flex\s*!important/.test(client),
);
check(
  'raises specificity with dshp-panel__toggle',
  /dshp-panel__toggle/.test(client),
);

// --- apply() must skip when a shell is present ---
check(
  'apply bails out when Tauri present',
  /function apply\(_ctx\)\s*\{\s*\/\/[^\n]*\n\s*if \(hasTauriShell\(\)\) return;/.test(client)
    || /if \(hasTauriShell\(\)\) return;/.test(client),
);

// --- import the factory logic in isolation and exercise it ---
// Evaluate the factory body with a stub ModuleLoader so we can call
// hasTauriShell / unhideCss without a browser.
const sandbox = {
  window: {
    __ModuleLoader__: {
      load({ factory }) {
        sandbox.exports = factory(() => ({}));
      },
    },
  },
  document: undefined,
};
// eslint-disable-next-line no-new-func
new Function('window', 'document', client)(sandbox.window, undefined);
const api = sandbox.exports;
check('factory produced exports', !!api && typeof api.apply === 'function');

check('unhideCss mentions both labels', /收起侧边栏/.test(api.unhideCss()) && /Collapse sidebar/.test(api.unhideCss()));
check('unhideCss uses !important display', /display:\s*inline-flex\s*!important/.test(api.unhideCss()));

// hasTauriShell under a bare window (phone browser) must be false.
// Recreate a fresh factory with no Tauri globals and a top-level window.
const bare = {
  window: {
    __ModuleLoader__: {
      load({ factory }) {
        bare.exports = factory(() => ({}));
      },
    },
    // top-level: parent === self
  },
};
bare.window.parent = bare.window;
new Function('window', 'document', client)(bare.window, undefined);
check('bare browser reports no Tauri', bare.exports.hasTauriShell() === false);

// With __TAURI__ present must be true.
const tauri = {
  window: {
    __TAURI__: {},
    __ModuleLoader__: {
      load({ factory }) {
        tauri.exports = factory(() => ({}));
      },
    },
  },
};
tauri.window.parent = tauri.window;
new Function('window', 'document', client)(tauri.window, undefined);
check('Tauri global reports shell', tauri.exports.hasTauriShell() === true);

// apply() in a bare browser must try to inject; stub document to observe.
let appended = 0;
const fakeDoc = {
  querySelector: () => null,
  createElement: () => ({ dataset: {}, textContent: '' }),
  head: { appendChild: () => { appended += 1; } },
};
const injectRun = {
  window: {
    __ModuleLoader__: {
      load({ factory }) {
        injectRun.exports = factory(() => ({}));
      },
    },
  },
};
injectRun.window.parent = injectRun.window;
new Function('window', 'document', client)(injectRun.window, fakeDoc);
injectRun.exports.apply({});
check('apply injects style in bare browser', appended === 1, `appended=${appended}`);

// apply() with Tauri must not inject.
appended = 0;
const tauriRun = {
  window: {
    __TAURI__: {},
    __ModuleLoader__: {
      load({ factory }) {
        tauriRun.exports = factory(() => ({}));
      },
    },
  },
};
tauriRun.window.parent = tauriRun.window;
new Function('window', 'document', client)(tauriRun.window, fakeDoc);
tauriRun.exports.apply({});
check('apply skips style when Tauri present', appended === 0, `appended=${appended}`);

if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed`);
  process.exit(1);
}
console.log('\nsidebar-unhide: ALL PASS');
