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
  FS_DOCTEXT: 'fs.doctext',
  FS_WRITE: 'fs.write',
  FS_MKDIR: 'fs.mkdir',
  FS_DELETE: 'fs.delete',
  FS_RENAME: 'fs.rename',
  FS_ROOTS: 'fs.roots',
  FS_SEARCH: 'fs.search',
  TERM_OPEN: 'term.open',
  TERM_RUN: 'term.run',
  TERM_INTERRUPT: 'term.interrupt',
  TERM_CLOSE: 'term.close',
  TERM_LIST: 'term.list',
  KERNELS_LIST: 'kernels.list',
  CODEX_GET: 'codex.get',
  CODEX_APPLY: 'codex.apply',
  CODEX_RESTORE: 'codex.restore',
  SESSIONS_LIST: 'sessions.list',
  SESSIONS_READ: 'sessions.read',
  CHAT_LIST: 'chat.list',
  CHAT_CREATE: 'chat.create',
  CHAT_RESUME: 'chat.resume',
  CHAT_SEND: 'chat.send',
  CHAT_CONFIG: 'chat.config',
  // What this conversation can be switched to. Asked for when the phone needs
  // it, because a kernel only declares its model list once a session exists.
  CHAT_MODELS: 'chat.models',
  CHAT_READ: 'chat.read',
  CHAT_CANCEL: 'chat.cancel',
  CHAT_CLOSE: 'chat.close',
  CHAT_APPROVE: 'chat.approve',
  // The command lines a conversation is running. Listing is also what starts
  // live output flowing, so a phone that is not looking is not flooded.
  CHAT_TERMINALS: 'chat.terminals',
  CHAT_TERMINAL_READ: 'chat.terminal.read',
  CHAT_TERMINAL_INPUT: 'chat.terminal.input',
  CHAT_TERMINAL_STOP: 'chat.terminal.stop',
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
  FS_RESULTS: 'fs.results',
  FS_DOCTEXT: 'fs.doctext',
  FS_WRITTEN: 'fs.written',
  FS_ROOTS: 'fs.roots',
  TERM_OPENED: 'term.opened',
  TERM_OUTPUT: 'term.output',
  TERM_EXIT: 'term.exit',
  TERM_LIST: 'term.list',
  KERNELS: 'kernels',
  CODEX_CONFIG: 'codex.config',
  SESSIONS: 'sessions',
  SESSION: 'session',
  CHATS: 'chats',
  CHAT: 'chat',
  CHAT_MODELS: 'chat.models',
  CHAT_EVENT: 'chat.event',
  CHAT_STATUS: 'chat.status',
  CHAT_TURN: 'chat.turn',
  CHAT_SENT: 'chat.sent',
  CHAT_CLOSED: 'chat.closed',
  CHAT_APPROVAL: 'chat.approval',
  CHAT_TERMINALS: 'chat.terminals',
  CHAT_TERMINAL: 'chat.terminal',
  CHAT_TERMINAL_OUTPUT: 'chat.terminal.output',
  CHAT_TERMINAL_INPUT: 'chat.terminal.input',
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
