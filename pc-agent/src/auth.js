/**
 * Token storage for TermDesk.
 *
 * On first run a random token is generated and written to a file the user can
 * read. The client pairs by pasting that token. The token never appears in
 * logs and is compared in constant time.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const TOKEN_DIR = path.join(os.homedir(), '.termdesk');
const TOKEN_FILE = path.join(TOKEN_DIR, 'token');

export function tokenPath() {
  return TOKEN_FILE;
}

/** Read the existing token, or create one on first run. */
export function loadOrCreateToken() {
  fs.mkdirSync(TOKEN_DIR, { recursive: true, mode: 0o700 });
  if (fs.existsSync(TOKEN_FILE)) {
    const existing = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
    if (existing.length >= 32) return existing;
  }
  const token = crypto.randomBytes(32).toString('base64url');
  fs.writeFileSync(TOKEN_FILE, `${token}\n`, { mode: 0o600 });
  return token;
}

/** Constant-time token comparison that tolerates length mismatch. */
export function tokenMatches(expected, presented) {
  if (typeof presented !== 'string' || presented.length === 0) return false;
  const a = Buffer.from(expected, 'utf8');
  const b = Buffer.from(presented, 'utf8');
  if (a.length !== b.length) {
    // Still burn a comparison so timing does not leak the length.
    crypto.timingSafeEqual(a, a);
    return false;
  }
  return crypto.timingSafeEqual(a, b);
}
