import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { listKernels, spawnSpec, kernelCommand, resolveOpenCode } from '../src/kernels/registry.js';

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
    assert.equal(resolved, executable);
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
    assert.match(rejections[0]?.reason ?? '', /脚本类型不支持.*\.cmd\/.bat/);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
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
