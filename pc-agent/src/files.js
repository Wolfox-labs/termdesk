/**
 * Filesystem access for the remote client.
 *
 * Security model: every path is resolved to an absolute form and checked
 * against a root allow-list before any operation. Symlinks are resolved with
 * realpath so a link inside an allowed root cannot be used to escape it.
 *
 * Reads are streamed for downloads and capped for text reads, because a phone
 * cannot usefully hold a multi-gigabyte file in memory.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';

/** Directories the client may browse. Override with TERMDESK_ROOTS=a;b;c */
function defaultRoots() {
  if (process.env.TERMDESK_ROOTS) {
    return process.env.TERMDESK_ROOTS.split(';').map((s) => s.trim()).filter(Boolean);
  }
  const home = os.homedir();
  return [
    home,
    ...['E:', 'D:', 'C:'].map((d) => `${d}\\`),
  ];
}

const ROOTS = defaultRoots();

export function allowedRoots() {
  return ROOTS.map((r) => path.resolve(r));
}

/**
 * Resolve a client-supplied path and confirm it stays inside an allowed root.
 * @returns {Promise<string>} the resolved absolute path
 * @throws {Error} with code 'outside_roots' when the path escapes every root
 */
export async function resolveSafePath(inputPath) {
  const raw = String(inputPath ?? '').trim();
  if (raw.length === 0) {
    const err = new Error('path is required');
    err.code = 'bad_path';
    throw err;
  }

  const absolute = path.resolve(raw);

  // Resolve symlinks when the target exists; otherwise validate the parent, so
  // a not-yet-created file is still confined to an allowed root.
  let probe = absolute;
  try {
    probe = await fs.realpath(absolute);
  } catch {
    try {
      const parent = await fs.realpath(path.dirname(absolute));
      probe = path.join(parent, path.basename(absolute));
    } catch {
      probe = absolute;
    }
  }

  const roots = await Promise.all(
    ROOTS.map(async (r) => {
      try { return await fs.realpath(path.resolve(r)); } catch { return path.resolve(r); }
    }),
  );

  const ok = roots.some((root) => {
    const rel = path.relative(root, probe);
    // Inside the root, or the root itself.
    return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
  });

  if (!ok) {
    const err = new Error('path is outside the allowed roots');
    err.code = 'outside_roots';
    throw err;
  }
  return absolute;
}

const TEXT_EXTENSIONS = new Set([
  '.txt', '.md', '.json', '.yml', '.yaml', '.toml', '.ini', '.cfg', '.conf',
  '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.py', '.rb', '.go', '.rs',
  '.java', '.kt', '.kts', '.c', '.h', '.cpp', '.hpp', '.cs', '.php', '.sh',
  '.ps1', '.bat', '.cmd', '.sql', '.html', '.htm', '.css', '.scss', '.xml',
  '.csv', '.log', '.env', '.gitignore', '.properties', '.gradle', '.vue', '.svg',
]);

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.heic', '.heif']);
const PDF_EXTENSIONS = new Set(['.pdf']);
const DOCX_EXTENSIONS = new Set(['.docx']);

/**
 * How the phone should try to show a file.
 *
 * dir    navigate instead of opening
 * text   read as text (editable)
 * image  fetch the bytes and draw them
 * pdf    fetch the bytes and rasterise with the platform renderer
 * docx   ask the PC for extracted text (the phone has no Word engine)
 * other  no viewer here; offer "open with" / download
 */
export function previewKind(name, isDir) {
  if (isDir) return 'dir';
  const ext = path.extname(name).toLowerCase();
  if (IMAGE_EXTENSIONS.has(ext)) return 'image';
  if (PDF_EXTENSIONS.has(ext)) return 'pdf';
  if (DOCX_EXTENSIONS.has(ext)) return 'docx';
  if (isProbablyText(name)) return 'text';
  return 'other';
}

export function isProbablyText(name) {
  const ext = path.extname(name).toLowerCase();
  if (TEXT_EXTENSIONS.has(ext)) return true;
  // Extensionless files (Dockerfile, Makefile, LICENSE) are usually text.
  return ext === '';
}

/** Directory listing with a stable sort: directories first, then by name. */
export async function listDirectory(dirPath) {
  const target = await resolveSafePath(dirPath);
  const entries = await fs.readdir(target, { withFileTypes: true });

  const items = [];
  for (const entry of entries) {
    const full = path.join(target, entry.name);
    let size = 0;
    let mtime = null;
    let isDir = entry.isDirectory();
    try {
      const st = await fs.stat(full);
      size = st.size;
      mtime = st.mtime.toISOString();
      isDir = st.isDirectory();
    } catch {
      // Broken symlink or permission denied — still list it, without stats.
    }
    items.push({
      name: entry.name,
      path: full,
      isDir,
      sizeBytes: isDir ? 0 : size,
      mtime,
      readable: isProbablyText(entry.name) && !isDir,
      kind: previewKind(entry.name, isDir),
    });
  }

  items.sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    return a.name.localeCompare(b.name, 'zh-CN');
  });

  return { path: target, parent: path.dirname(target), items };
}

const MAX_TEXT_BYTES = 2 * 1024 * 1024; // 2 MB is far more than a phone can edit

/** Read a text file, refusing anything too large or clearly binary. */
export async function readTextFile(filePath) {
  const target = await resolveSafePath(filePath);
  const st = await fs.stat(target);
  if (st.isDirectory()) {
    const err = new Error('path is a directory');
    err.code = 'is_directory';
    throw err;
  }
  if (st.size > MAX_TEXT_BYTES) {
    const err = new Error(`file is too large to edit (${Math.round(st.size / 1024 / 1024)} MB, limit 2 MB)`);
    err.code = 'too_large';
    throw err;
  }
  const buffer = await fs.readFile(target);
  // Reject content containing NUL bytes: that is a binary file regardless of
  // its extension, and showing it as text would produce garbage.
  if (buffer.includes(0)) {
    const err = new Error('file appears to be binary');
    err.code = 'binary';
    throw err;
  }
  return { path: target, text: buffer.toString('utf8'), sizeBytes: st.size, mtime: st.mtime.toISOString() };
}

/** Write text back to an existing file. */
export async function writeTextFile(filePath, text) {
  const target = await resolveSafePath(filePath);
  await fs.writeFile(target, String(text ?? ''), 'utf8');
  const st = await fs.stat(target);
  return { path: target, sizeBytes: st.size, mtime: st.mtime.toISOString() };
}

/** Create an empty file or a new directory. */
export async function createEntry(parentDir, name, kind) {
  const cleanName = String(name ?? '').trim();
  if (cleanName.length === 0 || cleanName.includes('/') || cleanName.includes('\\')) {
    const err = new Error('invalid name');
    err.code = 'bad_name';
    throw err;
  }
  if (cleanName === '.' || cleanName === '..') {
    const err = new Error('invalid name');
    err.code = 'bad_name';
    throw err;
  }
  const parent = await resolveSafePath(parentDir);
  const target = path.join(parent, cleanName);

  if (kind === 'dir') {
    await fs.mkdir(target, { recursive: false });
  } else {
    // wx: fail if it already exists, rather than silently truncating a file.
    const handle = await fs.open(target, 'wx');
    await handle.close();
  }
  return { path: target };
}

/** Delete a file or an empty directory. Refuses to remove a non-empty directory. */
export async function deleteEntry(targetPath) {
  const target = await resolveSafePath(targetPath);
  const st = await fs.stat(target);
  if (st.isDirectory()) {
    const entries = await fs.readdir(target);
    if (entries.length > 0) {
      const err = new Error('directory is not empty');
      err.code = 'not_empty';
      throw err;
    }
    await fs.rmdir(target);
  } else {
    await fs.unlink(target);
  }
  return { path: target };
}

/** Rename/move within the allowed roots. */
export async function renameEntry(fromPath, toName) {
  const from = await resolveSafePath(fromPath);
  const cleanName = String(toName ?? '').trim();
  if (cleanName.length === 0 || cleanName.includes('/') || cleanName.includes('\\')) {
    const err = new Error('invalid name');
    err.code = 'bad_name';
    throw err;
  }
  const to = await resolveSafePath(path.join(path.dirname(from), cleanName));
  await fs.rename(from, to);
  return { from, to };
}

/**
 * Directories a name search does not descend into.
 *
 * These are build outputs and package caches: they hold tens of thousands of
 * files whose names match almost any query, and walking them would turn a phone
 * search into a multi-minute scan of the whole disk.
 */
const SEARCH_SKIP_DIRS = new Set([
  'node_modules', '.git', '__pycache__', '.venv', 'venv', '.gradle', '.cache',
  '.npm', '.cargo', '.rustup', '.m2', '.next', '.nuxt', '.idea', '.vs',
  'AppData', 'System Volume Information', 'Windows',
]);

/**
 * Find files by name under a directory.
 *
 * Bounded on purpose: a result cap, a depth cap, a directory budget and a wall
 * clock, because the caller is a phone waiting on a socket. When any of them is
 * hit the answer says so, rather than pretending the list is complete.
 */
export async function searchFiles(startPath, query, options = {}) {
  const root = await resolveSafePath(startPath);
  const raw = String(query ?? '').trim();
  if (raw.length === 0) {
    const err = new Error('query is required');
    err.code = 'bad_query';
    throw err;
  }
  const needle = raw.toLowerCase();
  const limit = Math.min(Math.max(Number(options.limit) || 200, 1), 500);
  const maxDepth = Math.min(Math.max(Number(options.maxDepth) || 10, 1), 24);
  const deadline = Date.now() + 6000;
  const dirBudget = 20000;

  const items = [];
  const stack = [{ dir: root, depth: 0 }];
  let scannedDirs = 0;
  let truncated = false;

  while (stack.length > 0) {
    if (items.length >= limit || scannedDirs >= dirBudget || Date.now() > deadline) {
      truncated = true;
      break;
    }
    const { dir, depth } = stack.pop();
    scannedDirs += 1;
    let entries;
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      continue; // unreadable directory: skip, do not fail the whole search
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      const isDir = entry.isDirectory();
      if (entry.name.toLowerCase().includes(needle)) {
        let size = 0;
        let mtime = null;
        try {
          const st = await fs.stat(full);
          size = st.size;
          mtime = st.mtime.toISOString();
        } catch { /* broken link or denied: list it without stats */ }
        items.push({
          name: entry.name,
          path: full,
          isDir,
          sizeBytes: isDir ? 0 : size,
          mtime,
          readable: !isDir && isProbablyText(entry.name),
          kind: previewKind(entry.name, isDir),
        });
        if (items.length >= limit) { truncated = true; break; }
      }
      if (isDir && depth < maxDepth && !SEARCH_SKIP_DIRS.has(entry.name) && !entry.name.startsWith('$')) {
        stack.push({ dir: full, depth: depth + 1 });
      }
    }
  }

  return {
    path: root,
    query: raw,
    items,
    truncated,
    scannedDirs,
  };
}

const MAX_DOCX_BYTES = 24 * 1024 * 1024;

/**
 * Read one entry out of a ZIP archive held in memory.
 *
 * A .docx is a ZIP, and the only part that matters for reading it is
 * `word/document.xml`. Node ships no ZIP reader, but the end-of-central-directory
 * record plus each local header is enough to locate and inflate exactly one
 * entry — no dependency, no shelling out to a converter that may not exist.
 */
function readZipEntry(buf, wantedName) {
  let eocd = -1;
  const floor = Math.max(0, buf.length - 22 - 65536);
  for (let i = buf.length - 22; i >= floor; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) return null;
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n += 1) {
    if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) return null;
    const method = buf.readUInt16LE(off + 10);
    const compressedSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOffset = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    if (name === wantedName) {
      const localNameLen = buf.readUInt16LE(localOffset + 26);
      const localExtraLen = buf.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + localNameLen + localExtraLen;
      const data = buf.subarray(start, start + compressedSize);
      if (method === 0) return data;
      if (method === 8) return zlib.inflateRawSync(data);
      return null;
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

/** Turn WordprocessingML into readable plain text, keeping paragraph breaks. */
export function docxXmlToText(xml) {
  return xml
    .replace(/<w:tab\b[^>]*\/>/g, '\t')
    .replace(/<w:br\b[^>]*\/>/g, '\n')
    .replace(/<\/w:p>/g, '\n')
    .replace(/<\/w:tr>/g, '\n')
    .replace(/<\/w:tc>/g, '\t')
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(Number(dec)))
    .replace(/&#x([0-9A-Fa-f]+);/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/&amp;/g, '&')
    .replace(/\t+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[ \t]+$/gm, '')
    .trim();
}

/**
 * Extract the text of a .docx.
 *
 * Deliberately no styling: the phone shows this as a text document, and a
 * faithful layout would need a Word engine that this machine does not
 * necessarily have. The bytes stay on the PC; only the text travels.
 */
export async function readDocxText(filePath) {
  const target = await resolveSafePath(filePath);
  const st = await fs.stat(target);
  if (st.isDirectory()) {
    const err = new Error('path is a directory');
    err.code = 'is_directory';
    throw err;
  }
  if (!DOCX_EXTENSIONS.has(path.extname(target).toLowerCase())) {
    const err = new Error('not a .docx');
    err.code = 'not_docx';
    throw err;
  }
  if (st.size > MAX_DOCX_BYTES) {
    const err = new Error(`docx is too large to read (${Math.round(st.size / 1024 / 1024)} MB, limit 24 MB)`);
    err.code = 'too_large';
    throw err;
  }
  const buf = await fs.readFile(target);
  const entry = readZipEntry(buf, 'word/document.xml');
  if (!entry) {
    const err = new Error('无法读取 docx 内容（不是有效的 Word 文档）');
    err.code = 'bad_docx';
    throw err;
  }
  const text = docxXmlToText(entry.toString('utf8'));
  return { path: target, text, sizeBytes: st.size, mtime: st.mtime.toISOString() };
}

export function maxTextBytes() {
  return MAX_TEXT_BYTES;
}
