import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { listKernels, spawnSpec, kernelCommand } from '../src/kernels/registry.js';

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
