/**
 * TermDesk wire protocol (v1).
 *
 * One JSON object per WebSocket text frame. Every frame carries a `type`.
 * The client authenticates with the first frame it sends; the server closes
 * the socket with 4401 if that frame is not a valid `auth`.
 */

export const PROTOCOL_VERSION = 1;

/** Client -> server frame types. */
export const C2S = {
  AUTH: 'auth',
  STATUS_GET: 'status.get',
  STATUS_SUBSCRIBE: 'status.subscribe',
  STATUS_UNSUBSCRIBE: 'status.unsubscribe',
  PROCS_LIST: 'procs.list',
  PROCS_KILL: 'procs.kill',
  SERVICES_LIST: 'services.list',
  SERVICE_ACTION: 'services.action',
  FS_LIST: 'fs.list',
  FS_READ: 'fs.read',
  FS_WRITE: 'fs.write',
  FS_MKDIR: 'fs.mkdir',
  FS_DELETE: 'fs.delete',
  FS_RENAME: 'fs.rename',
  FS_ROOTS: 'fs.roots',
  TERM_OPEN: 'term.open',
  TERM_RUN: 'term.run',
  TERM_INTERRUPT: 'term.interrupt',
  TERM_CLOSE: 'term.close',
  TERM_LIST: 'term.list',
  AI_ENGINES: 'ai.engines',
  AI_SUBMIT: 'ai.submit',
  AI_TASKS: 'ai.tasks',
  AI_TASK_GET: 'ai.task',
  AI_CANCEL: 'ai.cancel',
  AI_RESET: 'ai.reset',
  CODEX_GET: 'codex.get',
  CODEX_APPLY: 'codex.apply',
  CODEX_RESTORE: 'codex.restore',
  SESSIONS_LIST: 'sessions.list',
  SESSIONS_READ: 'sessions.read',
  CHAT_LIST: 'chat.list',
  CHAT_CREATE: 'chat.create',
  CHAT_SEND: 'chat.send',
  CHAT_READ: 'chat.read',
  CHAT_CANCEL: 'chat.cancel',
  CHAT_CLOSE: 'chat.close',
  PING: 'ping',
};

/** Server -> client frame types. */
export const S2C = {
  AUTH_OK: 'auth.ok',
  AUTH_FAIL: 'auth.fail',
  HELLO: 'hello',
  STATUS: 'status',
  PROCS: 'procs',
  SERVICES: 'services',
  FS_LISTING: 'fs.listing',
  FS_FILE: 'fs.file',
  FS_WRITTEN: 'fs.written',
  FS_ROOTS: 'fs.roots',
  TERM_OPENED: 'term.opened',
  TERM_OUTPUT: 'term.output',
  TERM_EXIT: 'term.exit',
  TERM_LIST: 'term.list',
  AI_ENGINES: 'ai.engines',
  AI_TASKS: 'ai.tasks',
  AI_TASK: 'ai.task',
  AI_STARTED: 'ai.started',
  AI_EVENT: 'ai.event',
  AI_FINISHED: 'ai.finished',
  CODEX_CONFIG: 'codex.config',
  SESSIONS: 'sessions',
  SESSION: 'session',
  CHATS: 'chats',
  CHAT: 'chat',
  CHAT_EVENT: 'chat.event',
  CHAT_STATUS: 'chat.status',
  CHAT_TURN: 'chat.turn',
  CHAT_SENT: 'chat.sent',
  CHAT_CLOSED: 'chat.closed',
  ACTION_RESULT: 'action.result',
  ERROR: 'error',
  PONG: 'pong',
};

/** WebSocket close code used when authentication fails. */
export const CLOSE_UNAUTHORIZED = 4401;

/**
 * Parse an inbound text frame.
 * @returns {{ok: true, value: object} | {ok: false, error: string}}
 */
export function parseFrame(raw) {
  let value;
  try {
    value = JSON.parse(raw);
  } catch {
    return { ok: false, error: 'frame is not valid JSON' };
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'frame must be a JSON object' };
  }
  if (typeof value.type !== 'string' || value.type.length === 0) {
    return { ok: false, error: 'frame is missing a string "type"' };
  }
  return { ok: true, value };
}

/** Serialize an outbound frame. */
export function encodeFrame(type, payload = {}) {
  return JSON.stringify({ v: PROTOCOL_VERSION, type, ...payload });
}
