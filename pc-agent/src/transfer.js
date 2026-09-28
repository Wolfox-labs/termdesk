/**
 * HTTP file transfer endpoints.
 *
 * Control operations (list, read, write, rename, delete) travel over the
 * WebSocket, but moving whole files does not: base64 inside JSON would inflate
 * every byte by a third and force the receiver to buffer the entire file in
 * memory. HTTP streaming on the same port keeps uploads and downloads bounded
 * and gives the client real byte progress.
 *
 * Both endpoints require the same pairing token as the WebSocket, presented as
 * `Authorization: Bearer <token>`.
 *
 * Large files (P5-4) use a chunked session so each request stays under the
 * Cloudflare free-tier body cap (100 MB). The client opens a session, PUTs
 * chunks at fixed offsets, then commits; the server assembles into a `.part`
 * file beside the target and renames on commit. Interrupted sessions can be
 * resumed from `GET /upload/session`.
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { resolveSafePath } from './files.js';
import { tokenMatches } from './auth.js';

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024; // 2 GB
/** Per-request body cap. Stays well under Cloudflare's 100 MB free-tier limit. */
const MAX_CHUNK_BYTES = 8 * 1024 * 1024;
const DEFAULT_CHUNK_BYTES = 4 * 1024 * 1024;
/** Abandon an unfinished session and delete its .part file after this long. */
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
const SESSION_SWEEP_MS = 10 * 60 * 1000;

/**
 * Live chunked-upload sessions.
 * uploadId -> { target, part, size, chunkSize, totalChunks, received:Set, createdAt }
 */
const sessions = new Map();

function sweepSessions() {
  const now = Date.now();
  for (const [id, s] of sessions) {
    if (now - s.createdAt > SESSION_TTL_MS) {
      sessions.delete(id);
      fsp.unlink(s.part).catch(() => {});
    }
  }
}
const sweeper = setInterval(sweepSessions, SESSION_SWEEP_MS);
sweeper.unref?.();

function unauthorized(res) {
  res.writeHead(401, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ok: false, code: 'unauthorized', message: 'invalid or missing token' }));
}

function fail(res, status, code, message) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ok: false, code, message }));
}

function ok(res, body) {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

/** Pull the bearer token out of the Authorization header. */
function bearerToken(req) {
  const header = req.headers.authorization ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

const TRANSFER_ROUTES = new Set([
  '/download',
  '/upload',
  '/upload/session',
  '/upload/session/commit',
]);

/**
 * Handle the transfer routes. Returns true when the request was handled.
 */
export async function handleTransferRequest(req, res, url, token) {
  if (!TRANSFER_ROUTES.has(url.pathname)) return false;

  if (!tokenMatches(token, bearerToken(req))) {
    unauthorized(res);
    return true;
  }

  if (url.pathname === '/download') {
    const target = url.searchParams.get('path');
    if (!target) {
      fail(res, 400, 'bad_path', 'query parameter "path" is required');
      return true;
    }
    let resolved;
    try {
      resolved = await resolveSafePath(target);
    } catch (err) {
      fail(res, 403, err.code ?? 'forbidden', err.message);
      return true;
    }
    await handleDownload(req, res, resolved);
    return true;
  }

  if (url.pathname === '/upload') {
    const target = url.searchParams.get('path');
    if (!target) {
      fail(res, 400, 'bad_path', 'query parameter "path" is required');
      return true;
    }
    let resolved;
    try {
      resolved = await resolveSafePath(target);
    } catch (err) {
      fail(res, 403, err.code ?? 'forbidden', err.message);
      return true;
    }
    await handleUpload(req, res, resolved, url);
    return true;
  }

  if (url.pathname === '/upload/session') {
    if (req.method === 'POST') await openSession(req, res, url);
    else if (req.method === 'PUT') await putChunk(req, res, url);
    else if (req.method === 'GET') await sessionStatus(res, url);
    else if (req.method === 'DELETE') await abortSession(res, url);
    else fail(res, 405, 'bad_method', 'use POST, PUT, GET or DELETE');
    return true;
  }

  if (url.pathname === '/upload/session/commit') {
    if (req.method !== 'POST') {
      fail(res, 405, 'bad_method', 'use POST');
      return true;
    }
    await commitSession(req, res, url);
    return true;
  }

  return false;
}

async function handleDownload(req, res, resolved) {
  let stat;
  try {
    stat = await fsp.stat(resolved);
  } catch {
    fail(res, 404, 'not_found', 'file not found');
    return;
  }
  if (stat.isDirectory()) {
    fail(res, 400, 'is_directory', 'cannot download a directory');
    return;
  }

  const filename = path.basename(resolved);
  res.writeHead(200, {
    'content-type': 'application/octet-stream',
    'content-length': String(stat.size),
    // RFC 5987 encoding keeps non-ASCII filenames intact.
    'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
  });

  const stream = fs.createReadStream(resolved);
  stream.on('error', () => res.destroy());
  // If the phone cancels a download, stop reading instead of draining the file.
  res.on('close', () => stream.destroy());
  stream.pipe(res);
}

async function handleUpload(req, res, resolved, url) {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > MAX_UPLOAD_BYTES) {
    fail(res, 413, 'too_large', `upload exceeds ${MAX_UPLOAD_BYTES} bytes`);
    return;
  }

  // `overwrite=1` makes the client opt in to replacing an existing file.
  // Read from the parsed query, not a substring of the raw URL, so a path that
  // merely contains that text cannot smuggle overwrite in.
  const overwrite = url.searchParams.get('overwrite') === '1';

  try {
    const existing = await fsp.stat(resolved).catch(() => null);
    if (existing && existing.isDirectory()) {
      fail(res, 400, 'is_directory', 'target is a directory');
      return;
    }
    if (existing && !overwrite) {
      fail(res, 409, 'exists', 'target already exists; pass overwrite=1 to replace');
      return;
    }

    await fsp.mkdir(path.dirname(resolved), { recursive: true });

    // Count bytes so an unbounded body cannot fill the disk, and abort as soon
    // as the cap is crossed.
    let received = 0;
    const limiter = new Transform({
      transform(chunk, _enc, cb) {
        received += chunk.length;
        if (received > MAX_UPLOAD_BYTES) {
          cb(new Error('upload exceeded the size limit'));
          return;
        }
        cb(null, chunk);
      },
    });

    // pipeline waits for the write stream's real 'finish' (file flushed and
    // closed). Resolving earlier raced the flush and produced ENOENT on stat.
    await pipeline(req, limiter, fs.createWriteStream(resolved));

    const stat = await fsp.stat(resolved);
    ok(res, { ok: true, path: resolved, sizeBytes: stat.size });
  } catch (err) {
    // A partial file is worse than none: always clean up on failure.
    await fsp.unlink(resolved).catch(() => {});
    const tooLarge = /size limit/.test(String(err?.message ?? ''));
    if (!res.headersSent) {
      if (tooLarge) fail(res, 413, 'too_large', 'upload exceeded the size limit');
      else fail(res, 500, 'upload_failed', String(err?.message ?? err));
    }
  }
}

// ---- P5-4 chunked sessions ----

/**
 * POST /upload/session?path=...&size=...&chunkSize=...&overwrite=1
 * Begin a chunked upload. Returns the uploadId and the chunk plan.
 */
async function openSession(req, res, url) {
  const target = url.searchParams.get('path');
  const size = Number(url.searchParams.get('size') ?? 0);
  const overwrite = url.searchParams.get('overwrite') === '1';
  const chunkSize = Math.min(
    MAX_CHUNK_BYTES,
    Math.max(64 * 1024, Number(url.searchParams.get('chunkSize') ?? DEFAULT_CHUNK_BYTES) || DEFAULT_CHUNK_BYTES),
  );

  if (!target) {
    fail(res, 400, 'bad_path', 'query parameter "path" is required');
    return;
  }
  if (!Number.isInteger(size) || size <= 0 || size > MAX_UPLOAD_BYTES) {
    fail(res, 400, 'bad_size', `size must be an integer in 1..${MAX_UPLOAD_BYTES}`);
    return;
  }

  let resolved;
  try {
    resolved = await resolveSafePath(target);
  } catch (err) {
    fail(res, 403, err.code ?? 'forbidden', err.message);
    return;
  }

  const existing = await fsp.stat(resolved).catch(() => null);
  if (existing && existing.isDirectory()) {
    fail(res, 400, 'is_directory', 'target is a directory');
    return;
  }
  if (existing && !overwrite) {
    fail(res, 409, 'exists', 'target already exists; pass overwrite=1 to replace');
    return;
  }

  await fsp.mkdir(path.dirname(resolved), { recursive: true });

  const uploadId = crypto.randomBytes(12).toString('base64url');
  // Keep the partial beside the target so resolveSafePath still confines it.
  const part = `${resolved}.tdup-${uploadId}.part`;
  const totalChunks = Math.ceil(size / chunkSize);

  await fsp.writeFile(part, ''); // create/truncate
  sessions.set(uploadId, {
    target: resolved,
    part,
    size,
    chunkSize,
    totalChunks,
    received: new Set(),
    createdAt: Date.now(),
  });

  ok(res, {
    ok: true,
    uploadId,
    path: resolved,
    sizeBytes: size,
    chunkSize,
    totalChunks,
  });
}

/** PUT /upload/session?uploadId=...&index=N — write one chunk at its fixed offset. */
async function putChunk(req, res, url) {
  const uploadId = url.searchParams.get('uploadId');
  const index = Number(url.searchParams.get('index'));
  const session = uploadId ? sessions.get(uploadId) : null;

  if (!session) {
    fail(res, 404, 'no_session', 'unknown or expired uploadId; start a new session');
    return;
  }
  if (!Number.isInteger(index) || index < 0 || index >= session.totalChunks) {
    fail(res, 400, 'bad_index', `index must be in 0..${session.totalChunks - 1}`);
    return;
  }

  const declared = Number(req.headers['content-length'] ?? 0);
  const expected = index === session.totalChunks - 1
    ? session.size - session.chunkSize * (session.totalChunks - 1)
    : session.chunkSize;
  if (declared > MAX_CHUNK_BYTES) {
    fail(res, 413, 'chunk_too_large', `chunk exceeds ${MAX_CHUNK_BYTES} bytes`);
    return;
  }
  if (declared > 0 && declared !== expected) {
    fail(res, 400, 'bad_chunk_size', `chunk ${index} must be ${expected} bytes, got ${declared}`);
    return;
  }

  try {
    // Write at a fixed offset so a retried or out-of-order chunk cannot
    // corrupt the assembly.
    const handle = await fsp.open(session.part, 'r+');
    try {
      const sink = handle.createWriteStream({ start: index * session.chunkSize });
      let received = 0;
      const limiter = new Transform({
        transform(chunk, _enc, cb) {
          received += chunk.length;
          if (received > expected) {
            cb(new Error('chunk body exceeded the expected size'));
            return;
          }
          cb(null, chunk);
        },
      });
      await pipeline(req, limiter, sink);
      if (received !== expected) {
        throw new Error(`chunk ${index} expected ${expected} bytes, received ${received}`);
      }
    } finally {
      await handle.close();
    }

    session.received.add(index);
    ok(res, {
      ok: true,
      uploadId,
      index,
      receivedChunks: session.received.size,
      totalChunks: session.totalChunks,
    });
  } catch (err) {
    fail(res, 500, 'chunk_failed', String(err?.message ?? err));
  }
}

/** GET /upload/session?uploadId=... — resume checkpoint. */
async function sessionStatus(res, url) {
  const uploadId = url.searchParams.get('uploadId');
  const session = uploadId ? sessions.get(uploadId) : null;
  if (!session) {
    fail(res, 404, 'no_session', 'unknown or expired uploadId');
    return;
  }
  ok(res, {
    ok: true,
    uploadId,
    path: session.target,
    sizeBytes: session.size,
    chunkSize: session.chunkSize,
    totalChunks: session.totalChunks,
    receivedChunks: [...session.received].sort((a, b) => a - b),
  });
}

/** DELETE /upload/session?uploadId=... — drop the session and its .part file. */
async function abortSession(res, url) {
  const uploadId = url.searchParams.get('uploadId');
  const session = uploadId ? sessions.get(uploadId) : null;
  if (!session) {
    ok(res, { ok: true, code: 'already_gone' });
    return;
  }
  sessions.delete(uploadId);
  await fsp.unlink(session.part).catch(() => {});
  ok(res, { ok: true, code: 'aborted' });
}

/**
 * POST /upload/session/commit?uploadId=...
 * Verify every chunk landed, then rename .part -> target.
 */
async function commitSession(req, res, url) {
  const uploadId = url.searchParams.get('uploadId');
  const session = uploadId ? sessions.get(uploadId) : null;
  if (!session) {
    fail(res, 404, 'no_session', 'unknown or expired uploadId');
    return;
  }

  const missing = [];
  for (let i = 0; i < session.totalChunks; i += 1) {
    if (!session.received.has(i)) missing.push(i);
  }
  if (missing.length > 0) {
    fail(res, 409, 'incomplete', `missing chunks: ${missing.slice(0, 20).join(',')}${missing.length > 20 ? '…' : ''}`);
    return;
  }

  try {
    const stat = await fsp.stat(session.part);
    if (stat.size !== session.size) {
      fail(res, 409, 'size_mismatch', `assembled ${stat.size} bytes, expected ${session.size}`);
      return;
    }
    await fsp.rename(session.part, session.target);
    sessions.delete(uploadId);
    const final = await fsp.stat(session.target);
    ok(res, { ok: true, path: session.target, sizeBytes: final.size, chunks: session.totalChunks });
  } catch (err) {
    fail(res, 500, 'commit_failed', String(err?.message ?? err));
  }
}
