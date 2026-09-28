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
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { resolveSafePath } from './files.js';
import { tokenMatches } from './auth.js';

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024; // 2 GB

function unauthorized(res) {
  res.writeHead(401, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ok: false, code: 'unauthorized', message: 'invalid or missing token' }));
}

function fail(res, status, code, message) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ok: false, code, message }));
}

/** Pull the bearer token out of the Authorization header. */
function bearerToken(req) {
  const header = req.headers.authorization ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

/**
 * Handle the transfer routes. Returns true when the request was handled.
 */
export async function handleTransferRequest(req, res, url, token) {
  if (url.pathname !== '/download' && url.pathname !== '/upload') return false;

  if (!tokenMatches(token, bearerToken(req))) {
    unauthorized(res);
    return true;
  }

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

  if (url.pathname === '/download') {
    await handleDownload(req, res, resolved);
  } else {
    await handleUpload(req, res, resolved);
  }
  return true;
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

async function handleUpload(req, res, resolved) {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > MAX_UPLOAD_BYTES) {
    fail(res, 413, 'too_large', `upload exceeds ${MAX_UPLOAD_BYTES} bytes`);
    return;
  }

  // `overwrite=1` makes the client opt in to replacing an existing file.
  const overwrite = req.url.includes('overwrite=1');

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
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, path: resolved, sizeBytes: stat.size }));
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
