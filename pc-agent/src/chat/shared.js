import { getKernel } from '../kernels/registry.js';
import { resolveDshRuntime } from '../kernels/dsh-runtime.js';

/**
 * Limits and timers shared by the chat manager and its method groups.
 *
 * They live here rather than in ../chat.js so a group module can read them
 * without importing the facade (which imports the groups back).
 */
const MAX_CHATS = 8;
const MAX_PROMPT_CHARS = 24000;

/**
 * How long a conversation with no traffic keeps its runtime alive.
 *
 * Each live chat holds a full Node harness with an open provider route (DSH) or
 * a kernel process (ACP), so an idle one is real memory on someone's desktop.
 * The clock is the manager's, not a socket's: a phone that disconnects must not
 * decide how long the conversation survives.
 */
const IDLE_TTL_MS = 45 * 60 * 1000;
const INIT_TIMEOUT_MS = 120 * 1000;
const PROMPT_TIMEOUT_MS = 30 * 60 * 1000;
const REAP_INTERVAL_MS = 60 * 1000;

/**
 * How recently a session's file must have been written for the phone to be warned that
 * somebody else may be in it.
 *
 * Two minutes rather than a few seconds: a turn that is thinking writes nothing for long
 * stretches, so a tight window would fall silent exactly while the desktop application is
 * in the middle of an answer — the case the warning exists for.
 */
const RECENT_WRITE_SECONDS = 120;

/**
 * How many messages may wait behind a running turn.
 *
 * Small on purpose: this is "I typed the next thing while it was answering", not a batch
 * interface. A queue nobody can see the end of is how a conversation ends up answering
 * questions the person has already moved past.
 */
const MAX_QUEUED_MESSAGES = 5;

export {
  MAX_CHATS,
  MAX_PROMPT_CHARS,
  IDLE_TTL_MS,
  INIT_TIMEOUT_MS,
  PROMPT_TIMEOUT_MS,
  REAP_INTERVAL_MS,
  RECENT_WRITE_SECONDS,
  MAX_QUEUED_MESSAGES,
};

/**
 * Read a kernel descriptor for the contract to answer about.
 *
 * `getKernel` already returns the public shape (id, transport, resume,
 * liveTerminals, modelRoute), so the contract needed no new lookup. It is named
 * here because two callers need it and because a single place to change is the
 * point of this refactor.
 */
function lookupKernel(id) {
  return getKernel(id);
}

/**
 * The DSH runtime this machine would start.
 *
 * Discovery lives in kernels/dsh-runtime.js because it is the same question the
 * kernel table answers: which entry, launched how, with which environment. The
 * installed desktop app keeps its CLI inside `app.asar`, so "the file exists" is
 * not a plain `fs.statSync` question — see kernels/asar.js.
 */
function dshRuntime() {
  return resolveDshRuntime();
}

export { lookupKernel, dshRuntime };
