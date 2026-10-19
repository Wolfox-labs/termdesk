/** Verify a Windows app image without launching its UI or using a system JVM as fallback. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const args = process.argv.slice(2);
const value = (key, fallback) => { const i = args.indexOf(`--${key}`); return i < 0 ? fallback : args[i + 1]; };
const image = path.resolve(value('image', 'desktop/build/compose/binaries/main/app/TermDesk'));
const jdk = value('jdk', process.env.JAVA_HOME);
assert.ok(jdk, 'Set JAVA_HOME or --jdk to the JDK used to package the application');
const app = path.join(image, 'app');
const release = fs.readFileSync(path.join(image, 'runtime', 'release'), 'utf8');
const modules = new Set((/^MODULES="([^"]+)"/m.exec(release)?.[1] || '').split(' '));
assert.ok(modules.has('java.base'), 'App image has no valid runtime module list');
const mainJar = fs.readdirSync(app).find(name => /^termdesk-desktop-.*\.jar$/.test(name));
assert.ok(mainJar, 'Application JAR is missing');
const jdeps = path.join(jdk, 'bin', process.platform === 'win32' ? 'jdeps.exe' : 'jdeps');
const required = execFileSync(jdeps, ['--ignore-missing-deps', '--multi-release', '21', '--recursive', '--class-path', path.join(app, '*'), '--print-module-deps', path.join(app, mainJar)], { encoding: 'utf8', windowsHide: true });
const requiredModules = required.trim().split(/\r?\n/).at(-1).split(',').filter(Boolean);
assert.ok(requiredModules.every(name => /^[a-z][a-z0-9.]+$/.test(name)), 'Unrecognized jdeps output');
const missingModules = requiredModules.filter(name => !modules.has(name));
assert.deepEqual(missingModules, [], `Bundled JVM misses modules: ${missingModules.join(', ')}. Do not launch/publish this image.`);
const agentRoot = path.join(app, 'resources', 'pc-agent');
for (const relative of ['src/server.js', 'package.json', 'node_modules/ws/package.json', 'node_modules/qrcode/package.json', 'node_modules/socks-proxy-agent/package.json']) {
  assert.ok(fs.statSync(path.join(agentRoot, relative)).isFile(), `Packaged Agent resource missing: ${relative}`);
}
const sourceVersion = JSON.parse(fs.readFileSync(new URL('../pc-agent/package.json', import.meta.url))).version;
const packagedVersion = JSON.parse(fs.readFileSync(path.join(agentRoot, 'package.json'))).version;
assert.equal(packagedVersion, sourceVersion, 'Packaged Agent version differs from source');
const result = { image: path.basename(image), version: packagedVersion, requiredModules, bundledModules: [...modules], agentResourcesPresent: true, guiLaunched: false };
const output = value('output');
if (output) { fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true }); fs.writeFileSync(output, JSON.stringify(result, null, 2)); }
console.log(JSON.stringify(result, null, 2));
