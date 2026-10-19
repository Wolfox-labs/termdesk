/**
 * Reading Electron `app.asar` archives from plain Node.
 *
 * The DeepSeek Harness desktop app ships its CLI *inside* `app.asar`, and the
 * installed launcher on this machine points at an older Roaming directory that
 * no longer exists. The only real entry is therefore an archive path such as
 *
 *   C:\...\DeepSeek Harness\resources\app.asar\dsh\node_modules\@deepseek-ai\dsh\lib\bin.js
 *
 * Plain Node cannot stat or read that path (it is not a directory on disk), so
 * discovery would report DSH as missing even though the runtime is installed and
 * healthy. An asar archive is a simple container — a pickle header, a JSON index
 * and concatenated payloads — and answering "is this entry present?" needs only
 * the header. That is all this module does: read-only, no extraction, no writes
 * into the user's installation.
 *
 * Header layout, measured on the installed 0.2.1-rc.1 archive (182 MB):
 *
 *   [0..3]   uint32  4
 *   [4..7]   uint32  size of the header pickle
 *   [8..11]  uint32  (part of the pickle, unused here)
 *   [12..15] uint32  length of the JSON index
 *   [16..]   JSON    the file tree
 *   payload starts at 8 + header-pickle size
 *
 * Getting the payload base wrong by the pickle's prefix is a silent ten-byte
 * shift rather than an error, which is why the offsets above are written down.
 */
import fs from 'node:fs';

const HEADER_BYTES = 16;
/** Archive path → { mtimeMs, size, index }. The tree of one archive is ~18k rows. */
const INDEX_CACHE = new Map();

/** True for a path that names something inside an asar archive. */
export function isAsarPath(file) {
  return typeof file === 'string' && /\.asar[\\/]/i.test(file);
}

/** The archive a path belongs to, or null. */
export function asarArchiveOf(file) {
  if (!isAsarPath(file)) return null;
  const at = file.toLowerCase().indexOf('.asar');
  return file.slice(0, at + '.asar'.length);
}

/** A forward-slash absolute entry path, the shape the index uses. */
function normalize(inner) {
  const joined = `/${String(inner).replace(/\\/g, '/')}`.replace(/\/+/g, '/');
  return joined.length > 1 && joined.endsWith('/') ? joined.slice(0, -1) : joined;
}

/**
 * The entry path inside the archive, whether the caller passed one
 * (`\dsh\lib\bin.js`) or a full path (`...\app.asar\dsh\lib\bin.js`).
 */
function innerOf(archive, inner) {
  const file = String(inner).replace(/\\/g, '/');
  const root = archive.replace(/\\/g, '/');
  return file.toLowerCase().startsWith(root.toLowerCase()) ? file.slice(root.length) : file;
}

/** The archive's file index, or null when it cannot be read. Cached by mtime+size. */
export function asarIndex(archive) {
  let stat;
  try { stat = fs.statSync(archive); } catch { return null; }
  if (!stat.isFile()) return null;
  const key = archive.toLowerCase();
  const cached = INDEX_CACHE.get(key);
  if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) return cached.index;

  let fd;
  let index;
  try {
    fd = fs.openSync(archive, 'r');
    const head = Buffer.alloc(HEADER_BYTES);
    if (fs.readSync(fd, head, 0, HEADER_BYTES, 0) !== HEADER_BYTES) return null;
    const headerSize = head.readUInt32LE(4);
    const jsonSize = head.readUInt32LE(12);
    // A sanity gate instead of a guess: an index larger than its own pickle, or
    // larger than the archive, cannot be a real header and must not be parsed.
    if (jsonSize <= 0 || jsonSize > headerSize || headerSize > stat.size) return null;
    const json = Buffer.alloc(jsonSize);
    if (fs.readSync(fd, json, 0, jsonSize, HEADER_BYTES) !== jsonSize) return null;
    const tree = JSON.parse(json.toString('utf8'));
    index = new Map();
    const walk = (node, prefix) => {
      for (const [name, entry] of Object.entries(node?.files ?? {})) {
        const entryPath = `${prefix}/${name}`;
        if (entry?.files) walk(entry, entryPath);
        else index.set(entryPath, { size: entry?.size ?? 0, offset: entry?.offset ?? 0, unpacked: Boolean(entry?.unpacked) });
      }
    };
    walk(tree, '');
  } catch {
    return null;
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
  INDEX_CACHE.set(key, { mtimeMs: stat.mtimeMs, size: stat.size, index });
  return index;
}

/** The index row for one entry, or null. Accepts an inner path or a full path. */
export function asarEntry(archive, inner) {
  const index = asarIndex(archive);
  if (!index) return null;
  const wanted = normalize(innerOf(archive, inner));
  return index.get(wanted) ?? index.get(wanted.toLowerCase()) ?? null;
}

/** Is this entry present inside the archive? */
export function asarHas(archive, inner) {
  return asarEntry(archive, inner) !== null;
}

/**
 * Does this path exist — as a real file, or as an entry inside an asar archive?
 *
 * The one predicate callers should use instead of `fs.statSync`, so an asar path
 * is not reported missing by a plain-Node process.
 */
export function fileExists(file) {
  if (!file || typeof file !== 'string') return false;
  if (!isAsarPath(file)) {
    try { return fs.statSync(file).isFile(); } catch { return false; }
  }
  const archive = asarArchiveOf(file);
  if (!archive) return false;
  const entry = asarEntry(archive, file);
  if (!entry) return false;
  // An unpacked entry was materialised next to the archive; either copy is real.
  return !entry.unpacked || fs.existsSync(`${archive}.unpacked${file.slice(archive.length)}`);
}
