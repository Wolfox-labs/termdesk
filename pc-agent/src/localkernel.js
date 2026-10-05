/**
 * The local kernel's delivery surface: the payload and the manifest that
 * describes it.
 *
 * The local kernel is a Termux userland that runs INSIDE the phone app, so it
 * has to get there somehow. Shipping it in the APK would make every install
 * pay for it; downloading it on demand keeps the app small and lets the payload
 * be rebuilt without rebuilding the client. This module is the "somehow":
 *
 *   GET /kernel/local      the manifest (size + sha256 + where things live)
 *   GET /kernel/local.pkg  the bytes, with Range support so a phone on a bad
 *                          connection can resume instead of starting over
 *
 * Both require the same bearer token as every other transfer, because the
 * payload is fetched over the network the phone already trusts.
 *
 * The payload itself is built by tools/build-local-kernel.mjs and lives in
 * `~/.termdesk/local-kernel` (override with TERMDESK_LOCAL_KERNEL_DIR). It is a
 * `.tar.gz` on purpose: Android ships toybox, whose tar handles -z natively and
 * restores symlinks correctly - a naive Java unzip does not, and a Termux
 * userland is mostly symlinks.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { tokenMatches } from './auth.js';

export const LOCAL_KERNEL_ROUTES = new Set(['/kernel/local', '/kernel/local.pkg']);

/** Where the built payload and its manifest live. */
export function localKernelDir() {
  const override = process.env.TERMDESK_LOCAL_KERNEL_DIR;
  if (override) return override;
  return path.join(os.homedir(), '.termdesk', 'local-kernel');
}

function bearerToken(req) {
  const header = req.headers.authorization ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

/** Read the manifest, reporting why it is missing rather than throwing. */
export function readLocalKernel() {
  const dir = localKernelDir();
  const manifestPath = path.join(dir, 'local-kernel.json');
  if (!fs.existsSync(manifestPath)) {
    return { ok: false, code: 'no_manifest', dir, message: `还没有构建本地内核载荷（找不到 ${manifestPath}）` };
  }
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    return { ok: false, code: 'bad_manifest', dir, message: `载荷清单读不出来：${String(err?.message ?? err)}` };
  }
  const packagePath = path.join(dir, manifest.package ?? '');
  const present = Boolean(manifest.package) && fs.existsSync(packagePath);
  return { ok: true, dir, manifest, packagePath, present, sizeBytes: present ? fs.statSync(packagePath).size : 0 };
}

/** Handle the two routes. Returns true when the request was handled. */
export function handleLocalKernelRequest(req, res, url, token) {
  if (!LOCAL_KERNEL_ROUTES.has(url.pathname)) return false;
  if (!tokenMatches(token, bearerToken(req))) {
    json(res, 401, { ok: false, code: 'unauthorized', message: '缺少或错误的令牌' });
    return true;
  }

  const found = readLocalKernel();
  if (url.pathname === '/kernel/local') {
    if (!found.ok) {
      json(res, 404, { ok: false, code: found.code, message: found.message });
      return true;
    }
    json(res, 200, {
      ok: true,
      // `present: false` means the manifest exists but its payload does not:
      // the phone must not start a download that cannot finish.
      present: found.present,
      sizeBytes: found.sizeBytes,
      manifest: found.manifest,
    });
    return true;
  }

  // /kernel/local.pkg
  if (!found.ok) {
    json(res, 404, { ok: false, code: found.code, message: found.message });
    return true;
  }
  if (!found.present) {
    json(res, 404, { ok: false, code: 'no_payload', message: `清单指向的载荷不存在：${found.manifest.package}` });
    return true;
  }

  const size = found.sizeBytes;
  const range = /^bytes=(\d*)-(\d*)$/.exec(String(req.headers.range ?? '').trim());
  const headers = {
    'content-type': 'application/gzip',
    'accept-ranges': 'bytes',
    'content-disposition': `attachment; filename="${found.manifest.package}"`,
    'cache-control': 'no-store',
  };

  if (range) {
    const start = range[1] ? Number(range[1]) : 0;
    const end = range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    if (Number.isNaN(start) || start > end || start >= size) {
      res.writeHead(416, { 'content-range': `bytes */${size}` });
      res.end();
      return true;
    }
    res.writeHead(206, {
      ...headers,
      'content-range': `bytes ${start}-${end}/${size}`,
      'content-length': String(end - start + 1),
    });
    if (req.method === 'HEAD') { res.end(); return true; }
    fs.createReadStream(found.packagePath, { start, end }).pipe(res);
    return true;
  }

  res.writeHead(200, { ...headers, 'content-length': String(size) });
  if (req.method === 'HEAD') { res.end(); return true; }
  fs.createReadStream(found.packagePath).pipe(res);
  return true;
}