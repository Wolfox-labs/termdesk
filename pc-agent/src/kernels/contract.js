/**
 * What the chat pipeline is allowed to know about a kernel.
 *
 * The registry already declares where a kernel lives and how to launch it. What it
 * did NOT declare is how it is DRIVEN, so the manager asked by name:
 *
 *   if (chat.engine === 'codex') return this.sendCodex(...)
 *   if (chat.engine === 'dsh') return { ok: false, code: 'resume_unsupported', ... }
 *
 * Eleven such questions across one file, and the answers were facts about the
 * kernel rather than about the manager. Adding a kernel meant reading the manager
 * to find out which method to add; forgetting one produced a kernel that work
 * everywhere except in `cancel`. That is the coupling this closes.
 *
 * The rules that keep it honest:
 *
 *   - every question below is answered from the kernel TABLE, never from a name in
 *     the calling code. A new kernel is registered in one place;
 *   - an unknown kernel gets the `none` driver and no capability, so a typo shows up
 *     as "nothing happens, and the log says which id was unknown" rather than as a
 *     crash or, worse, a wrong kernel's behaviour;
 *   - a capability is not a promise that the kernel is INSTALLED. Availability is
 *     the registry's separate question, and conflating the two is how a list ends up
 *     offering a button that cannot work.
 *
 * Pure functions over an entry, so `tools/kernel-contract-test.js` can pin every
 * answer - including for kernels that do not exist yet.
 */

/** Every driver the manager knows how to run. */
export const TURN_DRIVERS = ['app-server', 'acp', 'sdk', 'none'];

/**
 * The transport a kernel speaks, as the table declares it.
 *
 * `transport` is the ONLY place this is stated. A second `driver` field was drafted
 * and dropped: for an ACP kernel the two would always say the same thing, and two
 * fields that must agree are two fields that can disagree. Deriving the driver from
 * the transport also means a kernel registered through `TERMDESK_ACP_KERNELS` or
 * `TERMDESK_CLI_KERNELS` at runtime is driven correctly without its declarer having
 * to know this module exists.
 *
 * An explicit `null` means "this build cannot drive it" and is NOT a fallback cue:
 * the unsupported entries declare it, and quietly reinterpreting that as their `tier`
 * would route them somewhere they cannot go.
 */
export function transportOf(entry) {
  if (!entry) return null;
  if (typeof entry.transport === 'string' && entry.transport.length > 0) return entry.transport;
  if (entry.transport === null) return null;
  // Entries predating `transport` carried the same fact as `adapter`.
  return typeof entry.adapter === 'string' && entry.adapter.length > 0 ? entry.adapter : null;
}

/**
 * Which method drives one turn.
 *
 * `app-server` is the kernel's own JSON protocol, `acp` is the shared adapter,
 * `sdk` is an in-process runtime, and `none` means this build cannot drive it - said
 * out loud rather than guessed, because routing an unknown kernel to the ACP adapter
 * would talk a protocol it may not speak.
 */
export function turnDriver(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  if (!entry) return 'none';
  switch (transportOf(entry)) {
    case 'app-server': return 'app-server';
    case 'acp': return 'acp';
    case 'cli': return 'acp'; // shim kernels are driven through the same adapter
    case 'sdk': return 'sdk';
    default: return 'none';
  }
}

/**
 * Whether "open this session from history" is implemented.
 *
 * The table's own `resume` flag is authoritative, and it stays authoritative when it
 * is restrictive: a kernel whose resume entry is not usable yet must be refused
 * HERE, so no list offers a continue button that would then fail. Reading it from the
 * table is also what keeps the refusal honest - it cannot drift from the reason
 * printed next to it.
 */
export function canResume(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  if (!entry) return false;
  if (turnDriver(entry) === 'none') return false;
  return entry.resume === true;
}

/**
 * Whether a live kernel session can report work it is doing right now.
 *
 * Asked by "进行中", whose promise is "what is still running on the PC". Only the
 * app-server kernel exposes that; asking the others costs a round trip and answers
 * nothing, which would make the refresh loop slower for no visible gain.
 */
export function collectsLiveTerminals(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  if (!entry) return false;
  return turnDriver(entry) === 'app-server';
}

/**
 * Whether the kernel owns its own session store, so "open" and "continue" are the
 * same operation and nothing is reconstructed from display text.
 */
export function ownsSessionStore(entryOrId, lookup) {
  const driver = turnDriver(entryOrId, lookup);
  return driver === 'acp' || driver === 'app-server';
}

/**
 * How a turn is stopped.
 *
 * `ask-kernel` means the kernel cancels its own turn and the conversation stays
 * resumable. `kill-process` means the runtime is ours and stopping means ending it.
 * Stated as a word rather than a boolean because the two paths differ in more than
 * truthiness - one keeps `threadId`, the other destroys it.
 */
export function cancelStyle(entryOrId, lookup) {
  const driver = turnDriver(entryOrId, lookup);
  if (driver === 'app-server') return 'ask-kernel';
  if (driver === 'sdk') return 'kill-process';
  if (driver === 'acp') return 'close-session';
  return 'none';
}

/**
 * Where a new conversation's default model comes from.
 *
 * Three genuinely different answers, so this is a declaration rather than a rule
 * derived from the transport:
 *
 *   - `kernel-config`: the kernel selects its model from its own config file, which
 *     is a surface TermDesk also manages (codex.get / codex.apply). Overriding it
 *     here would silently disagree with what that page shows.
 *   - `pinned`: the runtime has a usable default, but the machine's owner can pin a
 *     different one - a cost decision, and a cost decision belongs to the owner.
 *   - `default`: whatever the runtime's own default route is.
 */
export const MODEL_ROUTES = ['kernel-config', 'pinned', 'default'];

export function modelRoute(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  if (!entry) return 'default';
  const declared = entry.modelRoute;
  if (typeof declared === 'string' && MODEL_ROUTES.includes(declared)) return declared;
  return 'default';
}

/**
 * Everything above, for one id, in the shape the manager and the phone both read.
 *
 * One function so a caller cannot answer half the questions from the table and half
 * from a name - which is exactly the state this replaced.
 */
export function kernelCapabilities(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  const driver = turnDriver(entry);
  if (!entry) {
    return {
      id: typeof entryOrId === 'string' ? entryOrId : null,
      known: false,
      transport: null,
      driver: 'none',
      resumable: false,
      liveTerminals: false,
      ownsSessionStore: false,
      modelRoute: 'default',
      cancel: 'none',
    };
  }
  return {
    id: entry.id,
    known: true,
    transport: transportOf(entry),
    driver,
    resumable: canResume(entry),
    liveTerminals: collectsLiveTerminals(entry),
    ownsSessionStore: ownsSessionStore(entry),
    modelRoute: modelRoute(entry),
    cancel: cancelStyle(entry),
  };
}

/**
 * A log line for an id this build cannot drive.
 *
 * Returned rather than thrown: an unknown kernel arrives from a phone that may be
 * newer than this agent, and answering "this build does not know `foo`" is useful
 * where crashing is not.
 */
export function unknownKernelMessage(entryOrId, lookup) {
  const entry = resolve(entryOrId, lookup);
  if (entry) return null;
  const id = typeof entryOrId === 'string' && entryOrId.length > 0 ? entryOrId : '(未指定)';
  return `这个版本的电脑端不认识内核 ${id}：请在电脑上更新 TermDesk`;
}

/**
 * Accept either an entry or an id.
 *
 * The manager holds engines as ids (they arrive from the phone), so both are
 * accepted and the lookup is injected - which is what lets the tests ask about
 * invented kernels without touching the real table.
 */
function resolve(entryOrId, lookup) {
  if (entryOrId && typeof entryOrId === 'object') return entryOrId;
  if (typeof entryOrId !== 'string' || entryOrId.length === 0) return null;
  if (typeof lookup !== 'function') return null;
  return lookup(entryOrId) ?? null;
}
