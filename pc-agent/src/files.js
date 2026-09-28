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

export function maxTextBytes() {
  return MAX_TEXT_BYTES;
}
