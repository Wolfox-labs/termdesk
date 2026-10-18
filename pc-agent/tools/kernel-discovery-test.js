import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listKernels, spawnSpec, kernelCommand, resolveOpenCode, chatEngineIds, probeKernel } from '../src/kernels/registry.js';
import { dshScriptFromShim, findNpmCommand } from '../src/kernels/discovery.js';

test('local discovery includes only executables found on this host', () => {
  const found = listKernels({ installedOnly: true });
  assert.ok(found.every((kernel) => kernel.available && kernel.path));
  assert.deepEqual(found.map((kernel) => kernel.id), listKernels().filter((kernel) => kernel.available).map((kernel) => kernel.id));
});

test('a stale explicit command cannot manufacture an installed kernel', () => {
  const old = process.env.TERMDESK_ACP_KERNELS;
  process.env.TERMDESK_ACP_KERNELS = JSON.stringify([{ id: 'missing-discovery-test', bin: path.join(os.tmpdir(), 'missing-termdesk-command-12345') }]);
  try {
    assert.equal(listKernels().find((kernel) => kernel.id === 'missing-discovery-test').available, false);
    assert.equal(listKernels({ installedOnly: true }).some((kernel) => kernel.id === 'missing-discovery-test'), false);
    assert.equal(spawnSpec('missing-discovery-test'), null);
  } finally {
    if (old === undefined) delete process.env.TERMDESK_ACP_KERNELS; else process.env.TERMDESK_ACP_KERNELS = old;
  }
});

test('an unknown registered kernel appears and disappears without changing brand names', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-discovery-'));
  const old = process.env.TERMDESK_KERNEL_MANIFEST_DIR;
  process.env.TERMDESK_KERNEL_MANIFEST_DIR = directory;
  const script = path.join(directory, 'agent.mjs');
  fs.writeFileSync(script, '// No process or model is executed by discovery.\n');
  fs.writeFileSync(path.join(directory, 'custom.json'), JSON.stringify({ id: 'arbitrary-installed-agent', label: 'My installed assistant', bin: process.execPath, preArgs: [script], args: ['acp'] }));

  try {
    const discovered = listKernels({ installedOnly: true }).find((kernel) => kernel.id === 'arbitrary-installed-agent');
    assert.equal(discovered.label, 'My installed assistant');
    assert.equal(discovered.selectable, true);
    assert.deepEqual(spawnSpec(discovered.id).args, [script, 'acp']);
    fs.unlinkSync(script);
    assert.equal(listKernels({ installedOnly: true }).some((kernel) => kernel.id === discovered.id), false);
  } finally {
    if (old === undefined) delete process.env.TERMDESK_KERNEL_MANIFEST_DIR; else process.env.TERMDESK_KERNEL_MANIFEST_DIR = old;
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('an ACP handshake failure blocks new chats until a changed entry passes', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-probe-cache-'));
  const failScript = path.join(directory, 'fail-acp.mjs');
  fs.writeFileSync(failScript, [
    "process.stdin.setEncoding('utf8');",
    "process.stdin.on('data', (chunk) => { for (const line of chunk.split('\\n')) { if (!line.trim()) continue; const req = JSON.parse(line); if (req.method === 'initialize') process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, error: { code: -32000, message: 'fixture handshake failure' } }) + '\\n'); } });",
  ].join('\n'));
  const keys = ['TERMDESK_ACP_KERNELS', 'TERMDESK_KERNEL_PATHS', 'TERMDESK_KERNEL_MANIFEST_DIR', 'TERMDESK_KERNEL_STATE'];
  const prior = Object.fromEntries(keys.map((key) => [key, process.env[key]]));
  process.env.TERMDESK_KERNEL_PATHS = path.join(directory, 'paths.json');
  process.env.TERMDESK_KERNEL_MANIFEST_DIR = path.join(directory, 'kernels');
  process.env.TERMDESK_KERNEL_STATE = path.join(directory, 'state.json');
  const id = 'probe-cache-fixture';
  const configure = (script) => {
    process.env.TERMDESK_ACP_KERNELS = JSON.stringify([{ id, label: 'Probe fixture', bin: process.execPath, preArgs: [script], args: ['acp'] }]);
  };
  try {
    configure(failScript);
    const failed = await probeKernel(id);
    assert.equal(failed.ok, false);
    const afterFailure = listKernels().find((kernel) => kernel.id === id);
    assert.equal(afterFailure.available, true);
    assert.match(afterFailure.detail, /^ACP 握手失败/);
    assert.equal(afterFailure.selectable, false);
    assert.equal(chatEngineIds().includes(id), false);

    configure(fileURLToPath(new URL('./fake-acp-agent.mjs', import.meta.url)));
    const recovered = await probeKernel(id);
    assert.equal(recovered.ok, true);
    const afterRecovery = listKernels().find((kernel) => kernel.id === id);
    assert.match(afterRecovery.detail, /^ACP · TermDesk Stub Kernel/);
    assert.equal(afterRecovery.selectable, true);
    assert.equal(chatEngineIds().includes(id), true);
  } finally {
    for (const [key, value] of Object.entries(prior)) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('a bare command name is resolved from the current PATH on each detection', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-path-'));
  const oldPath = process.env.PATH;
  const command = path.join(directory, process.platform === 'win32' ? 'dynamic-assistant.exe' : 'dynamic-assistant');
  if (process.platform === 'win32') fs.copyFileSync(process.execPath, command);
  else fs.writeFileSync(command, '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  process.env.PATH = directory;
  const entry = { resolve: () => 'dynamic-assistant' };
  try {
    assert.equal(kernelCommand(entry).bin, command);
    fs.unlinkSync(command);
    assert.equal(kernelCommand(entry), null);
  } finally {
    if (oldPath === undefined) delete process.env.PATH; else process.env.PATH = oldPath;
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('Windows OpenCode discovery prefers a native executable on PATH', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-opencode-path-'));
  const executable = path.join(directory, 'opencode.exe');
  fs.copyFileSync(process.execPath, executable);
  try {
    const resolved = resolveOpenCode([], {
      env: { PATH: directory, PATHEXT: '.EXE;.CMD;.BAT' },
      platform: 'win32',
      npmRoot: path.join(directory, 'missing-npm'),
      localPrograms: path.join(directory, 'missing-programs'),
    });
    assert.equal(resolved, executable);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('Windows OpenCode discovery accepts the package Node entry when no native executable exists', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-opencode-node-'));
  const packageDir = path.join(directory, 'npm', 'node_modules', 'opencode-ai');
  const entry = path.join(packageDir, 'bin', 'opencode.js');
  fs.mkdirSync(path.dirname(entry), { recursive: true });
  fs.writeFileSync(entry, '#!/usr/bin/env node\n// discovery fixture only\n');
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ bin: { opencode: 'bin/opencode.js' } }));
  try {
    const resolved = resolveOpenCode([], {
      env: { PATH: path.join(directory, 'empty-path'), PATHEXT: '.EXE;.CMD;.BAT' },
      platform: 'win32',
      npmRoot: path.join(directory, 'npm'),
      localPrograms: path.join(directory, 'missing-programs'),
    });
    assert.deepEqual(resolved, { bin: process.execPath, args: [entry] });
    const command = kernelCommand({ transport: 'acp', acpArgs: ['acp'], resolve: () => resolved });
    assert.equal(command.bin, process.execPath);
    assert.deepEqual(command.args, [entry]);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('Windows OpenCode discovery follows a declared npm native executable entry', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-opencode-npm-exe-'));
  const packageDir = path.join(directory, 'npm', 'node_modules', 'opencode-ai');
  const executable = path.join(packageDir, 'bin', 'opencode.exe');
  fs.mkdirSync(path.dirname(executable), { recursive: true });
  fs.copyFileSync(process.execPath, executable);
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ bin: { opencode: 'bin/opencode.exe' } }));
  try {
    const resolved = resolveOpenCode([], {
      env: { PATH: path.join(directory, 'empty-path'), PATHEXT: '.EXE;.CMD;.BAT' },
      platform: 'win32',
      npmRoot: path.join(directory, 'npm'),
      localPrograms: path.join(directory, 'missing-programs'),
    });
    assert.deepEqual(resolved, { bin: executable, args: [] });
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('Windows OpenCode discovery preserves the explicit unsupported reason for npm .cmd shims', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-opencode-cmd-'));
  const shim = path.join(directory, 'opencode.cmd');
  fs.writeFileSync(shim, '@echo off\r\necho fixture\r\n');
  const rejections = [];
  try {
    const resolved = resolveOpenCode(rejections, {
      env: { PATH: directory, PATHEXT: '.EXE;.CMD;.BAT' },
      platform: 'win32',
      npmRoot: path.join(directory, 'missing-npm'),
      localPrograms: path.join(directory, 'missing-programs'),
    });
    assert.equal(resolved, null);
    assert.equal(rejections[0]?.path, shim);
    assert.match(rejections[0]?.reason ?? '', /启动器.*npm bin 元数据/);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('npm commands resolve from whichever PATH prefix supplies the package metadata', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-npm-prefix-'));
  const prefixes = ['install-a', 'install-b'].map((name) => path.join(directory, name));
  const targets = [];
  for (const prefix of prefixes) {
    const packageDir = path.join(prefix, 'node_modules', '@anthropic-ai', 'claude-code');
    const target = path.join(packageDir, 'bin', 'claude.exe');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(process.execPath, target);
    fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({
      name: '@anthropic-ai/claude-code', version: 'fixture', bin: { claude: 'bin/claude.exe' },
    }));
    fs.writeFileSync(path.join(prefix, 'claude.cmd'),
      `@"%dp0%\\node_modules\\@anthropic-ai\\claude-code\\bin\\claude.exe" %*\r\n`);
    targets.push(target);
  }

  try {
    for (let index = 0; index < prefixes.length; index += 1) {
      const result = findNpmCommand('claude', {
        env: { PATH: prefixes[index], PATHEXT: '.EXE;.CMD;.BAT' },
        platform: 'win32',
      });
      assert.equal(result?.bin, targets[index]);
      assert.deepEqual(result?.args, []);
      assert.equal(result?.source, 'npm-native');
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('npm metadata recognizes extensionless Node shebangs and DSH only accepts a Node script', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-dsh-npm-'));
  const packageDir = path.join(directory, 'node_modules', '@deepseek-ai', 'dsh');
  const script = path.join(packageDir, 'bin', 'dsh');
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.writeFileSync(script, '#!/usr/bin/env node\n// inert package fixture\n');
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh', bin: { dsh: 'bin/dsh' } }));
  fs.writeFileSync(path.join(directory, 'dsh.cmd'), `@"%dp0%\\node_modules\\@deepseek-ai\\dsh\\bin\\dsh" %*\r\n`);
  const prior = {
    path: process.env.PATH,
    dsh: process.env.TERMDESK_DSH,
    kernelPaths: process.env.TERMDESK_KERNEL_PATHS,
    manifests: process.env.TERMDESK_KERNEL_MANIFEST_DIR,
    state: process.env.TERMDESK_KERNEL_STATE,
  };
  process.env.PATH = directory;
  delete process.env.TERMDESK_DSH;
  process.env.TERMDESK_KERNEL_PATHS = path.join(directory, 'paths.json');
  process.env.TERMDESK_KERNEL_MANIFEST_DIR = path.join(directory, 'kernels');
  process.env.TERMDESK_KERNEL_STATE = path.join(directory, 'state.json');
  try {
    const found = findNpmCommand('dsh', { env: { PATH: directory, PATHEXT: '.EXE;.CMD;.BAT' }, platform: 'win32', packages: ['@deepseek-ai/dsh'], nodeOnly: true });
    assert.equal(found?.bin, process.execPath);
    assert.equal(found?.script, script);
    assert.deepEqual(found?.args, [script]);
    const dsh = listKernels().find((kernel) => kernel.id === 'dsh');
    assert.equal(dsh?.available, true);
    assert.equal(dsh?.path, script);
    assert.deepEqual(spawnSpec('dsh')?.args, [script]);
  } finally {
    if (prior.path === undefined) delete process.env.PATH; else process.env.PATH = prior.path;
    for (const [key, value] of [['TERMDESK_DSH', prior.dsh], ['TERMDESK_KERNEL_PATHS', prior.kernelPaths], ['TERMDESK_KERNEL_MANIFEST_DIR', prior.manifests], ['TERMDESK_KERNEL_STATE', prior.state]]) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('Claude Code is visible as installed while its unimplemented transport stays unselectable', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-claude-detect-'));
  const packageDir = path.join(directory, 'node_modules', '@anthropic-ai', 'claude-code');
  const target = path.join(packageDir, 'bin', 'claude.exe');
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.copyFileSync(process.execPath, target);
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ bin: { claude: 'bin/claude.exe' } }));
  fs.writeFileSync(path.join(directory, 'claude.cmd'),
    `@"%dp0%\\node_modules\\@anthropic-ai\\claude-code\\bin\\claude.exe" %*\r\n`);
  const prior = {
    path: process.env.PATH,
    kernelPaths: process.env.TERMDESK_KERNEL_PATHS,
    manifests: process.env.TERMDESK_KERNEL_MANIFEST_DIR,
    state: process.env.TERMDESK_KERNEL_STATE,
    claude: process.env.TERMDESK_CLAUDE,
  };
  process.env.PATH = directory;
  process.env.TERMDESK_KERNEL_PATHS = path.join(directory, 'paths.json');
  process.env.TERMDESK_KERNEL_MANIFEST_DIR = path.join(directory, 'kernels');
  process.env.TERMDESK_KERNEL_STATE = path.join(directory, 'state.json');
  delete process.env.TERMDESK_CLAUDE;
  try {
    const claude = listKernels().find((kernel) => kernel.id === 'claude-code');
    assert.equal(claude?.available, true);
    assert.equal(claude?.tier, 'unsupported');
    assert.equal(claude?.selectable, false);
    assert.equal(claude?.manifest.transport, null);
    assert.equal(chatEngineIds().includes('claude-code'), false);
  } finally {
    if (prior.path === undefined) delete process.env.PATH; else process.env.PATH = prior.path;
    for (const [key, value] of [['TERMDESK_KERNEL_PATHS', prior.kernelPaths], ['TERMDESK_KERNEL_MANIFEST_DIR', prior.manifests], ['TERMDESK_KERNEL_STATE', prior.state], ['TERMDESK_CLAUDE', prior.claude]]) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('DSH shim discovery requires an existing declared Node entry and never launches the wrapper', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-dsh-shim-'));
  const staleTarget = path.join(directory, 'missing', 'bin.js');
  const shim = path.join(directory, 'dsh.ps1');
  fs.writeFileSync(shim, `$dshBin = '${staleTarget}'\n`);
  const rejected = [];
  try {
    assert.equal(dshScriptFromShim({ env: { PATH: directory }, rejected }), null);
    assert.match(rejected[0]?.reason ?? '', /目标运行时缺失/);

    const entry = path.join(directory, 'runtime', 'bin.js');
    fs.mkdirSync(path.dirname(entry), { recursive: true });
    fs.writeFileSync(entry, '// inert discovery fixture\n');
    fs.writeFileSync(shim, `$dshBin = '${entry}'\n`);
    assert.equal(dshScriptFromShim({ env: { PATH: directory }, rejected: [] }), entry);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('Codex npm discovery preserves its Node prelude and an explicit stale override stays unavailable', { skip: process.platform !== 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-codex-npm-'));
  const packageDir = path.join(directory, 'node_modules', '@openai', 'codex');
  const script = path.join(packageDir, 'bin', 'codex.js');
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.writeFileSync(script, '// inert npm fixture\n');
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ name: '@openai/codex', bin: { codex: 'bin/codex.js' } }));
  fs.writeFileSync(path.join(directory, 'codex.cmd'), `@"%dp0%\\node_modules\\@openai\\codex\\bin\\codex.js" %*\r\n`);
  const prior = { path: process.env.PATH, codex: process.env.TERMDESK_CODEX };
  process.env.PATH = directory;
  delete process.env.TERMDESK_CODEX;
  try {
    assert.deepEqual(spawnSpec('codex'), { bin: process.execPath, args: [script] });
    assert.equal(listKernels().find((kernel) => kernel.id === 'codex').path, script);
    process.env.TERMDESK_CODEX = path.join(directory, 'not-installed.exe');
    const codex = listKernels().find((kernel) => kernel.id === 'codex');
    assert.equal(codex.available, false);
    assert.equal(codex.path, null);
    assert.equal(spawnSpec('codex'), null);
  } finally {
    if (prior.path === undefined) delete process.env.PATH; else process.env.PATH = prior.path;
    if (prior.codex === undefined) delete process.env.TERMDESK_CODEX; else process.env.TERMDESK_CODEX = prior.codex;
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('Node-hosted SDK scripts do not need an executable bit', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-sdk-'));
  const script = path.join(directory, 'sdk.js');
  fs.writeFileSync(script, '// SDK entry\n', { mode: 0o644 });
  try {
    assert.equal(kernelCommand({ transport: 'sdk', resolve: () => script }).script, script);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('copied Windows executables and nonexecutable files are not local POSIX kernels', { skip: process.platform === 'win32' }, () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-posix-'));
  const binary = path.join(directory, 'desktop.exe');
  fs.writeFileSync(binary, 'MZ fake desktop binary', { mode: 0o755 });
  const script = path.join(directory, 'not-executable');
  fs.writeFileSync(script, '#!/bin/sh\n', { mode: 0o644 });
  try {
    assert.equal(kernelCommand({ resolve: () => binary }), null);
    assert.equal(kernelCommand({ resolve: () => script }), null);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
