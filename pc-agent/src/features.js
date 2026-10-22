/**
 * Feature packs: the five chat families (web / ppt / poster / table / image)
 * delivered the same way the local kernel is.
 *
 *   GET /features            what this computer can hand out (index + presence)
 *   GET /feature/<id>        one pack's manifest (size + sha256 + file list)
 *   GET /feature/<id>.pkg    the bytes, with Range so a phone on a bad link can
 *                            resume instead of starting over
 *
 * Why packs and not a bigger APK: a pack is templates, prompts and presets, so
 * a new deck blueprint or page skeleton no longer waits for an app release. It
 * is also why the phone can show the menu entry as "download me" instead of
 * pretending a feature exists when its data has not arrived.
 *
 * The payloads are built by tools/build-feature-pack.mjs into
 * `~/.termdesk/features` (override with TERMDESK_FEATURE_DIR). Same bearer token
 * as every other transfer: a pack is fetched over the link the phone already
 * trusts, so there is no second authentication story.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { tokenMatches } from './auth.js';

export const FEATURE_ROUTES = new Set(['/features']);

/** Where the built packs and their manifests live. */
export function featuresDir() {
  const override = process.env.TERMDESK_FEATURE_DIR;
  if (override) return override;
  return path.join(os.homedir(), '.termdesk', 'features');
}

function bearerToken(req) {
  const header = req.headers.authorization ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(String(header).trim());
  return match ? match[1].trim() : null;
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

/** The pack id a path names, or null when it is not a feature route. */
export function featureIdOf(pathname) {
  const match = /^\/feature\/([a-z0-9-]+)(\.pkg)?$/.exec(pathname);
  return match ? { id: match[1], payload: Boolean(match[2]) } : null;
}

/** Read one pack's manifest. Reports why it is unusable instead of throwing. */
export function readPack(id) {
  const dir = featuresDir();
  const manifestPath = path.join(dir, `${id}.json`);
  if (!fs.existsSync(manifestPath)) {
    return { ok: false, code: 'no_manifest', dir, message: `还没有构建功能包 ${id}（找不到 ${manifestPath}）` };
  }
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    return { ok: false, code: 'bad_manifest', dir, message: `功能包清单读不出来：${String(err?.message ?? err)}` };
  }
  const packagePath = path.join(dir, manifest.packageName ?? '');
  const present = Boolean(manifest.packageName) && fs.existsSync(packagePath);
  return {
    ok: true,
    dir,
    manifest,
    packagePath,
    present,
    sizeBytes: present ? fs.statSync(packagePath).size : 0,
  };
}

/** Everything this computer can hand out. Missing manifests are listed as such. */
export function listPacks() {
  const dir = featuresDir();
  const indexPath = path.join(dir, 'index.json');
  if (!fs.existsSync(indexPath)) {
    return { ok: false, code: 'no_index', dir, message: `还没有构建任何功能包（找不到 ${indexPath}）` };
  }
  let index;
  try {
    index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
  } catch (err) {
    return { ok: false, code: 'bad_index', dir, message: `功能包索引读不出来：${String(err?.message ?? err)}` };
  }
  const packs = Array.isArray(index.packs) ? index.packs : [];
  return {
    ok: true,
    dir,
    packs: packs.map((pack) => {
      const found = readPack(pack.id);
      return {
        ...pack,
        // present=false 表示清单有、包还没构建：手机端不该开始一次完成不了的下载。
        present: found.ok ? found.present : false,
      };
    }),
  };
}

/** Handle the feature routes. Returns true when the request was handled. */
export function handleFeatureRequest(req, res, url, token) {
  const target = featureIdOf(url.pathname);
  if (!FEATURE_ROUTES.has(url.pathname) && !target) return false;
  if (!(typeof token === 'function' ? token(bearerToken(req)) : tokenMatches(token, bearerToken(req)))) {
    json(res, 401, { ok: false, code: 'unauthorized', message: '缺少或错误的令牌' });
    return true;
  }

  if (url.pathname === '/features') {
    const listed = listPacks();
    if (!listed.ok) {
      json(res, 404, { ok: false, code: listed.code, message: listed.message, packs: [] });
      return true;
    }
    json(res, 200, { ok: true, packs: listed.packs });
    return true;
  }

  const found = readPack(target.id);
  if (!target.payload) {
    if (!found.ok) {
      json(res, 404, { ok: false, code: found.code, message: found.message });
      return true;
    }
    json(res, 200, {
      ok: true,
      present: found.present,
      sizeBytes: found.sizeBytes,
      manifest: found.manifest,
    });
    return true;
  }

  // /feature/<id>.pkg
  if (!found.ok) {
    json(res, 404, { ok: false, code: found.code, message: found.message });
    return true;
  }
  if (!found.present) {
    json(res, 404, {
      ok: false,
      code: 'no_payload',
      message: `清单指向的包不存在：${found.manifest.packageName}`,
    });
    return true;
  }

  const size = found.sizeBytes;
  const range = /^bytes=(\d*)-(\d*)$/.exec(String(req.headers.range ?? '').trim());
  const headers = {
    'content-type': 'application/zip',
    'accept-ranges': 'bytes',
    'content-disposition': `attachment; filename="${found.manifest.packageName}"`,
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
