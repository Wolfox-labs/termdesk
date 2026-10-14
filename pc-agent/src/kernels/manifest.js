/**
 * Normalized capability and configuration manifest exposed to every client.
 *
 * The registry answers how a kernel is launched. This module answers what a
 * client may safely promise: which session operations exist, which settings
 * are live, and which requested controls are only planned. Keeping support
 * states explicit prevents the phone from rendering a control that the adapter
 * silently ignores.
 */
import {
  cancelStyle,
  canResume,
  collectsLiveTerminals,
  modelRoute,
  ownsSessionStore,
  resolveKernelEntry,
  transportOf,
  turnDriver,
} from './contract.js';

export const CONFIG_SUPPORT = ['supported', 'discovered', 'planned', 'unsupported'];
export const CONFIG_APPLY = ['next-turn', 'session', 'runtime-restart', 'profile'];

function field(id, type, support, apply, extra = {}) {
  return { id, type, support, apply, ...extra };
}

/**
 * Build the public manifest for one registry entry.
 *
 * This is deliberately conservative. "planned" means a future editor may show
 * the field as unavailable with a reason; it never means the current adapter
 * accepts the value.
 */
export function manifestFor(entryOrId, lookup) {
  const entry = resolveKernelEntry(entryOrId, lookup);
  if (!entry) {
    return {
      version: 1,
      id: typeof entryOrId === 'string' ? entryOrId : null,
      known: false,
      transport: null,
      driver: 'none',
      session: { list: false, read: false, resume: false, fork: false, nativeTranscript: false },
      turn: { cancel: 'none', queue: false },
      model: { source: 'default', dynamic: false, switch: false, add: false },
      config: { scope: 'none', liveApply: false, requiresRestart: false, schema: [] },
    };
  }

  const transport = transportOf(entry);
  const driver = turnDriver(entry);
  const isAcp = transport === 'acp';
  const isCli = transport === 'cli';
  const isCodex = transport === 'app-server';
  const nativeTranscript = isCodex || (isAcp && !entry.shim);
  const modelSwitch = isCodex || isAcp || isCli;

  const schema = [
    field('provider', 'text', isCodex ? 'supported' : (transport === 'sdk' ? 'planned' : 'unsupported'), 'profile', {
      label: '服务商', secret: false,
    }),
    field('model', 'enum', modelSwitch ? (isAcp ? 'discovered' : 'supported') : 'unsupported', 'next-turn', {
      label: '模型', valuesSource: isAcp ? 'kernel-session' : 'config',
    }),
    field('effort', 'enum', (isCodex || isAcp) ? 'supported' : 'planned', 'next-turn', {
      label: '推理强度', valuesSource: isCodex ? 'codex-config' : 'kernel-session',
    }),
    field('contextWindow', 'number', isCodex ? 'supported' : 'planned', 'profile', {
      label: '上下文长度', min: 1, unit: 'tokens',
    }),
    field('maxOutput', 'number', 'planned', 'next-turn', {
      label: '最大输出', min: 1, unit: 'tokens',
    }),
    field('inputModalities', 'multi-enum', isCodex ? 'discovered' : (isAcp ? 'discovered' : 'planned'), 'profile', {
      label: '输入模态', valuesSource: 'model-capabilities',
    }),
    field('outputModalities', 'multi-enum', 'planned', 'profile', {
      label: '输出模态', valuesSource: 'model-capabilities',
    }),
    field('mode', 'enum', isAcp ? 'discovered' : 'unsupported', 'session', {
      label: '权限模式', valuesSource: 'kernel-session',
    }),
  ];

  return {
    version: 1,
    id: entry.id,
    known: true,
    label: entry.label,
    tier: entry.tier,
    transport,
    driver,
    session: {
      list: ownsSessionStore(entry) || Boolean(entry.resume),
      read: nativeTranscript,
      resume: canResume(entry),
      fork: isCodex || isAcp,
      nativeTranscript,
    },
    turn: {
      cancel: cancelStyle(entry),
      queue: true,
      liveTerminals: collectsLiveTerminals(entry),
    },
    model: {
      source: modelRoute(entry),
      dynamic: isAcp,
      switch: modelSwitch,
      add: false,
    },
    config: {
      // A profile is machine-wide; a session is attached to one native thread.
      scope: isCodex ? 'profile' : (isAcp ? 'session' : (transport === 'sdk' ? 'runtime' : 'session')),
      liveApply: isAcp || isCodex,
      requiresRestart: transport === 'sdk',
      schema,
    },
  };
}
