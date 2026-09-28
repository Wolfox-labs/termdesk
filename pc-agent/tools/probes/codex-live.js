/**
 * Verify the Codex provider flow against the RUNNING agent, read-only first.
 *
 * Reading the live config is safe and is what the phone does on opening
 * Settings. The apply path is exercised for real only behind --apply, because
 * it rewrites ~/.codex/config.toml; the sandboxed suite
 * (codex-config-test.js) is the place that proves writes.
 *
 *   node tools/codex-live.js           # read-only
 *   node tools/codex-live.js --apply   # also performs one real apply + restore
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WebSocket } from 'ws';

const PORT = Number(process.env.TERMDESK_PORT || 7420);
const HOST = process.env.TERMDESK_HOST || '127.0.0.1';
const APPLY = process.argv.includes('--apply');
const token = fs.readFileSync(path.join(os.homedir(), '.termdesk', 'token'), 'utf8').trim();

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const ws = new WebSocket(`ws://${HOST}:${PORT}`);
const frames = [];
ws.on('message', (raw) => frames.push(JSON.parse(raw.toString())));

const send = (o) => ws.send(JSON.stringify(o));
const waitFor = async (fn, ms = 30000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 100));
  }
  return null;
};
const find = (t) => frames.filter((f) => f.type === t);

const CONFIG = path.join(os.homedir(), '.codex', 'config.toml');
const beforeText = fs.existsSync(CONFIG) ? fs.readFileSync(CONFIG, 'utf8') : null;

await new Promise((r) => ws.on('open', () => {
  send({ type: 'auth', token });
  setTimeout(r, 800);
}));

try {
  send({ type: 'codex.get' });
  const frame = await waitFor(() => find('codex.config')[0]);
  check('reads the live Codex config over the wire', Boolean(frame), frame ? 'received' : 'no frame');

  const cfg = frame.config;
  check('reports the config path', cfg.configPath.endsWith('config.toml'), cfg.configPath);
  check('reports the active model', typeof cfg.model === 'string' && cfg.model.length > 0, cfg.model);
  check('reports the active provider', typeof cfg.modelProvider === 'string', cfg.modelProvider);
  check('reports reasoning effort', typeof cfg.reasoningEffort === 'string', cfg.reasoningEffort);

  const tpl = frame.templates?.[0];
  check('sends a provider template', Boolean(tpl?.id), `${tpl?.id} · ${tpl?.models?.join(',')}`);
  check('template carries the context window', tpl?.contextWindow === 1048576, String(tpl?.contextWindow));
  check('template carries reasoning levels', Array.isArray(tpl?.reasoningLevels) && tpl.reasoningLevels.length === 3,
    tpl?.reasoningLevels?.join('/'));
  check('template carries the key prefix for validation', tpl?.keyPrefix === 'sk-', tpl?.keyPrefix);

  // Security: the phone must never receive the API key.
  const serialized = JSON.stringify(frame);
  const realKeyMatch = beforeText && /experimental_bearer_token\s*=\s*"([^"]+)"/.exec(beforeText);
  if (realKeyMatch) {
    check('never transmits the stored API key', !serialized.includes(realKeyMatch[1]),
      realKeyMatch[1].slice(0, 6) + '…');
  } else {
    console.log('SKIP  no stored key in config to check against');
  }
  check('reports only whether a key exists', cfg.providers.every((p) => typeof p.hasToken === 'boolean'));

  // Rejects bad input without writing.
  send({ type: 'codex.apply', providerId: 'not-a-provider', model: 'x' });
  const bad = await waitFor(() => find('action.result').find((f) => f.action === 'codex.apply'));
  check('rejects an unknown provider over the wire', bad?.ok === false && bad?.code === 'bad_provider', bad?.message);

  const afterReject = fs.existsSync(CONFIG) ? fs.readFileSync(CONFIG, 'utf8') : null;
  check('config unchanged after a rejected apply', afterReject === beforeText);

  if (APPLY) {
    console.log('\n  (performing a real apply, then restoring)');
    const model = cfg.model;
    const effort = cfg.reasoningEffort ?? 'high';

    send({
      type: 'codex.apply',
      providerId: 'deepseek',
      model: tpl.models.includes(model) ? model : tpl.models[0],
      reasoningEffort: tpl.reasoningLevels.includes(effort) ? effort : tpl.defaultReasoning,
    });
    const applied = await waitFor(() => find('action.result').find((f) => f.action === 'codex.apply'));
    check('applies over the wire', applied?.ok === true, applied?.message);

    const afterApply = fs.readFileSync(CONFIG, 'utf8');
    check('config actually changed on disk', afterApply !== beforeText);
    check('untouched sections preserved', !beforeText || beforeText
      .split('\n')
      .filter((l) => /^\[(desktop|projects|skills)/.test(l))
      .every((l) => afterApply.includes(l)));

    send({ type: 'codex.restore' });
    const restored = await waitFor(() => find('action.result').find((f) => f.action === 'codex.restore'));
    check('restores from the phone', restored?.ok === true, restored?.message);

    send({ type: 'codex.get' });
    const after = await waitFor(() => find('codex.config').length >= 2);
    check('config is readable again after restore', Boolean(after));
  } else {
    console.log('SKIP  real apply (pass --apply to exercise it)');
  }
} catch (err) {
  check('codex live harness completed', false, err.message);
}

ws.close();
const failures = results.filter((r) => !r.passed).length;
console.log(`\nCodex live: ${results.length - failures}/${results.length} passed`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300);
