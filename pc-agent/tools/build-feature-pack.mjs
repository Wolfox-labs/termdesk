/**
 * Build the feature packs the phone installs on demand.
 *
 * Input : ../packs/<id>/   (feature.json + the files that pack ships)
 * Output: $d/<id>-<version>.zip  +  $d/<id>.json (manifest)  +  $d/index.json
 *
 * Why a hand-written zip writer instead of a dependency: a pack is a few hundred
 * kilobytes of HTML/text/images, and the phone already has java.util.zip. STORE
 * (no deflate) keeps the writer to ~60 lines, keeps the pack inspectable with any
 * unzip tool, and the payload is small enough that compression buys nothing但一个
 * 依赖。Kernels use tar.gz because they carry symlinks and permissions; packs do
 * not, so zip is the simpler contract on both ends.
 *
 * The manifest is what the phone verifies: size and sha256 of the bytes actually
 * received, so a truncated download cannot be installed as a working pack.
 *
 *   node tools/build-feature-pack.mjs           # every pack under ../packs
 *   node tools/build-feature-pack.mjs web ppt   # just these
 *   TERMDESK_FEATURE_DIR may override the output directory.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PACK_SRC = path.resolve(HERE, '..', '..', 'packs');
const OUT_DIR = process.env.TERMDESK_FEATURE_DIR ?? path.join(os.homedir(), '.termdesk', 'features');

const REQUIRED = ['id', 'name', 'version', 'minApp'];
const VERSION_RE = /^\d+\.\d+\.\d+$/;

/** Every file a pack ships, sorted so two builds of the same input match. */
function collect(root) {
  const out = [];
  const walk = (dir, rel) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      const next = rel ? `${rel}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(full, next);
      else out.push({ full, name: next });
    }
  };
  walk(root, '');
  return out;
}

/**
 * A STORE-method zip holding `entries` ([{name, data}]).
 *
 * Layout follows the spec: local headers + data, then the central directory and
 * the end-of-central-directory record. Timestamps are pinned so the same input
 * produces the same bytes, which is what makes the sha256 comparable.
 */
function zipOf(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  const DOS_TIME = 0;
  const DOS_DATE = 33; // 1980-01-01, the spec's own epoch
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const data = entry.data;
    const crc = zlib.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);           // version needed
    local.writeUInt16LE(0x0800, 6);       // UTF-8 names
    local.writeUInt16LE(0, 8);            // STORE
    local.writeUInt16LE(DOS_TIME, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, name, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(DOS_TIME, 12);
    central.writeUInt16LE(DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);         // extra
    central.writeUInt16LE(0, 32);         // comment
    central.writeUInt16LE(0, 34);         // disk
    central.writeUInt16LE(0, 36);         // internal attrs
    central.writeUInt32LE(0, 38);         // external attrs
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);

    offset += local.length + name.length + data.length;
  }
  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...locals, ...centrals, end]);
}

function readManifest(id) {
  const file = path.join(PACK_SRC, id, 'feature.json');
  if (!fs.existsSync(file)) throw new Error(`${id}: 缺少 feature.json`);
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const key of REQUIRED) {
    if (typeof json[key] !== 'string' || json[key].trim() === '') {
      throw new Error(`${id}: feature.json 缺少 ${key}`);
    }
  }
  if (!VERSION_RE.test(json.version)) throw new Error(`${id}: version 要写成 x.y.z，收到 ${json.version}`);
  if (!VERSION_RE.test(json.minApp)) throw new Error(`${id}: minApp 要写成 x.y.z，收到 ${json.minApp}`);
  return json;
}

export function buildPack(id, outDir = OUT_DIR) {
  const dir = path.join(PACK_SRC, id);
  if (!fs.existsSync(dir)) throw new Error(`没有这个功能包：${id}`);
  const meta = readManifest(id);
  const files = collect(dir).filter((f) => f.name !== 'feature.json');
  if (files.length === 0) throw new Error(`${id}: 包里一个文件都没有`);

  const entries = files.map((f) => ({ name: f.name, data: fs.readFileSync(f.full) }));
  const zip = zipOf(entries);
  fs.mkdirSync(outDir, { recursive: true });
  const packageName = `${id}-${meta.version}.zip`;
  fs.writeFileSync(path.join(outDir, packageName), zip);

  const manifest = {
    ...meta,
    packageName,
    sizeBytes: zip.length,
    sha256: crypto.createHash('sha256').update(zip).digest('hex'),
    fileCount: entries.length,
    // 手机端按这张表核对：少一个文件就是坏包。
    files: entries.map((e) => ({ path: e.name, bytes: e.data.length })),
    builtAt: new Date().toISOString(),
  };
  fs.writeFileSync(path.join(outDir, `${id}.json`), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

function main() {
  const wanted = process.argv.slice(2).filter((a) => !a.startsWith('--'));
  const ids = wanted.length > 0
    ? wanted
    : fs.readdirSync(PACK_SRC, { withFileTypes: true })
      .filter((e) => e.isDirectory() && !e.name.startsWith('.'))
      .map((e) => e.name)
      .sort();

  const built = [];
  for (const id of ids) {
    const manifest = buildPack(id);
    built.push(manifest);
    console.log(`built ${manifest.packageName}  ${manifest.sizeBytes} bytes  ${manifest.fileCount} files`);
  }

  const index = {
    packs: built.map(({ id, name, version, minApp, summary, packageName, sizeBytes, sha256 }) => ({
      id, name, version, minApp, summary, packageName, sizeBytes, sha256,
    })),
  };
  fs.writeFileSync(path.join(OUT_DIR, 'index.json'), `${JSON.stringify(index, null, 2)}\n`);
  console.log(`index: ${built.length} pack(s) -> ${OUT_DIR}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    main();
  } catch (err) {
    console.error(err.message);
    process.exit(1);
  }
}
