/**
 * Static consistency checks that need no running agent.
 *
 * Catches drift between the wire protocol and the server handler table
 * before it reaches a phone. Run via `npm test`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = (name) => fs.readFileSync(path.join(root, 'src', name), 'utf8');

const protocol = src('protocol.js');
const server = src('server.js');
const handlers = src('handlers.js');

const failures = [];
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(name);
};

/** Every C2S/S2C literal declared in protocol.js. */
function declaredFrames() {
  const c2s = new Set();
  const s2c = new Set();
  const grab = (block, into) => {
    for (const m of block.matchAll(/(?:^|,)\s*[A-Z0-9_]+:\s*'([^']+)'/g)) into.add(m[1]);
  };
  const c2sBlock = /export const C2S = \{([\s\S]*?)\};/.exec(protocol)?.[1] ?? '';
  const s2cBlock = /export const S2C = \{([\s\S]*?)\};/.exec(protocol)?.[1] ?? '';
  grab(c2sBlock, c2s);
  grab(s2cBlock, s2c);
  return { c2s, s2c };
}

const { c2s, s2c } = declaredFrames();

check('protocol declares C2S frames', c2s.size > 20, `${c2s.size} frames`);
check('protocol declares S2C frames', s2c.size > 20, `${s2c.size} frames`);

// Every C2S frame the client may send should have a switch arm in handlers.js
// (or an explicit pre-switch handler, like auth in server.js). `term.input` was
// declared but never handled — that is exactly the drift this check exists to catch.
const handled = new Set();
for (const m of handlers.matchAll(/case C2S\.([A-Z0-9_]+)/g)) handled.add(m[1]);
// auth is handled before the switch (first-frame gate), not as a case arm.
if (/frame\.type !== C2S\.AUTH|frame\.type === C2S\.AUTH|C2S\.AUTH\)/.test(server)) {
  handled.add('AUTH');
}

// Map C2S constant names to their wire strings, then compare.
const nameToWire = new Map();
const c2sBlock = /export const C2S = \{([\s\S]*?)\};/.exec(protocol)?.[1] ?? '';
for (const m of c2sBlock.matchAll(/([A-Z0-9_]+):\s*'([^']+)'/g)) {
  nameToWire.set(m[1], m[2]);
}

const unhandled = [...nameToWire.entries()]
  .filter(([name]) => !handled.has(name))
  .map(([name, wire]) => `${name} (${wire})`);

check(
  'every C2S frame has a server handler',
  unhandled.length === 0,
  unhandled.length ? `unhandled: ${unhandled.join(', ')}` : `${handled.size} handlers`,
);

// S2C frames must be referenced somewhere in src/.
const allSrc = fs.readdirSync(path.join(root, 'src'))
  .filter((f) => f.endsWith('.js'))
  .map((f) => src(f))
  .join('\n');

const missingS2c = [...s2c].filter((wire) => !allSrc.includes(`'${wire}'`) && !allSrc.includes(`"${wire}"`));
check(
  'every S2C frame is produced somewhere',
  missingS2c.length === 0,
  missingS2c.length ? `never emitted: ${missingS2c.join(', ')}` : `${s2c.size} frames`,
);

// Protocol docs live in README (abbreviated) and REQUIREMENTS.md (full table).
// Accept a frame if either document mentions it in backticks.
const readme = fs.readFileSync(path.join(root, '..', 'README.md'), 'utf8');
const requirements = fs.existsSync(path.join(root, '..', 'REQUIREMENTS.md'))
  ? fs.readFileSync(path.join(root, '..', 'REQUIREMENTS.md'), 'utf8')
  : '';
const docs = readme + '\n' + requirements;
const readmeMissing = [...c2s].filter((w) => !docs.includes(`\`${w}\``));
check(
  'docs list every C2S frame',
  readmeMissing.length === 0,
  readmeMissing.length ? `missing: ${readmeMissing.join(', ')}` : '',
);

// One version for the whole product.
//
// The phone, the desktop window and this agent have to agree, because "which
// build is this?" is the first question a frozen version has to answer. The
// number had already drifted once (the status route said 0.2.0 while the
// handshake said 0.1.0), which is exactly why this is checked rather than
// trusted.
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const androidGradle = fs.readFileSync(path.join(root, '..', 'android', 'app', 'build.gradle.kts'), 'utf8');
const desktopGradle = fs.readFileSync(path.join(root, '..', 'desktop', 'build.gradle.kts'), 'utf8');
const androidVersion = /versionName\s*=\s*"([^"]+)"/.exec(androidGradle)?.[1] ?? null;
const desktopVersion = /packageVersion\s*=\s*"([^"]+)"/.exec(desktopGradle)?.[1] ?? null;
// The installer format only accepts MAJOR.MINOR.BUILD, so the desktop window
// declares the numeric core of the product version and nothing else.
const numericCore = String(pkg.version).split('-')[0];
check('the agent declares a version', typeof pkg.version === 'string' && pkg.version.length > 0, String(pkg.version));
check('the phone declares the same version', androidVersion === pkg.version, `android=${androidVersion} agent=${pkg.version}`);
check(
  'the desktop window declares the same version',
  desktopVersion === numericCore,
  `desktop=${desktopVersion} expected=${numericCore}`,
);
check('the version is served, not just embedded', /\bversion:\s*AGENT_VERSION\b/.test(server), 'status route');
check('and the handshake names it', /termdesk-pc-agent\/\$\{AGENT_VERSION\}/.test(server), 'auth.ok');

if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed`);
  process.exit(1);
}
console.log('\nAll symbol checks passed');
