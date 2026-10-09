/**
 * A conversation in the list has to be recognisable, or the list is useless.
 *
 * This exists because of a real complaint: the owner had a conversation open on the computer,
 * looked at the phone, and could not find it. It WAS in the list — under `session-f8a45aa2-…`,
 * because the DSH session index carried `title: null` for every stored session with a comment
 * saying it would be "filled in on demand", and nothing ever filled it in.
 *
 * The titles are now read from the head of each session file (the header and the opening
 * message live there, since DSH appends a frame as the session grows). This pins the parts that
 * are easy to get wrong and expensive to notice:
 *
 *   - the first USER message becomes the title, not the harness's injected preamble — naming
 *     every conversation after the same paragraph would be worse than showing the id;
 *   - a session that has not said anything yet gets no title rather than a fabricated one;
 *   - a file whose frames are torn (a session being written right now) does not throw;
 *   - the multi-frame layout is handled, because that is what real session files are.
 *
 * Free, offline: the fixtures are written to a temp directory, and no session of the owner's is
 * read.
 *
 *   node tools/session-title-test.js
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'termdesk-session-titles-'));
process.env.TERMDESK_DSH_DIR = root;

/** One appended-frame session file, the way the runtime writes it. */
function writeSession({ workspace, id, lines, frameSize = 1 }) {
  const dir = path.join(root, 'sessions', workspace, id);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'session.v4.jsonl.zstd');
  const text = lines.map((l) => JSON.stringify(l)).join('\n') + '\n';
  // Split into several frames on purpose: a single frame would not exercise the reader.
  const chunk = Math.ceil(text.length / frameSize);
  const parts = [];
  for (let i = 0; i < text.length; i += chunk) {
    parts.push(zlib.zstdCompressSync(Buffer.from(text.slice(i, i + chunk), 'utf8')));
  }
  fs.writeFileSync(file, Buffer.concat(parts));
  return file;
}

const header = (cwd) => ({ type: 'session', id: null, cwd, createdAt: Date.now() });
/**
 * A user-role message in the shape the runtime actually writes.
 *
 * `data.content` (not `data.message.content`), and `data.source.kind` is what says who wrote
 * it: `user` is the person, anything else is context the harness injected.
 */
const userSays = (text, sourceKind = 'user') => ({
  type: 'user/message',
  seq: 2,
  time: Date.now(),
  data: { content: [{ type: 'text', text }], source: { kind: sourceKind } },
});

try {
  writeSession({
    workspace: '--C-Users-test-termdesk--',
    id: 'session-with-a-question',
    lines: [
      header('C:\\Users\\test\\termdesk'),
      // The harness injects its own preamble as a user-role message; a title taken from it
      // would name every conversation in the list after the same paragraph.
      userSays('You are an AI agent powered by DeepSeek Harness. The checkout is at ...', 'plugin'),
      userSays('把通知做成可靠的，后台也要能收到'),
    ],
    frameSize: 3,
  });

  writeSession({
    workspace: '--C-Users-test-empty--',
    id: 'session-that-has-not-spoken',
    lines: [header('C:\\Users\\test\\empty')],
  });

  writeSession({
    workspace: '--D-work--',
    id: 'session-being-written',
    lines: [
      header('D:\\work'),
      userSays('half a file'),
      // A later frame, so that tearing the tail does not tear the message: that is what a
      // session being appended to right now actually looks like — early frames complete, the
      // newest one cut off mid-write.
      { type: 'assistant/chunk', seq: 3, time: Date.now(), data: { chunk: { type: 'text', text: 'still going' } } },
    ],
    frameSize: 4,
  });

  // A torn final frame is what a session being appended to right now looks like on disk.
  {
    const file = path.join(root, 'sessions', '--D-work--', 'session-being-written', 'session.v4.jsonl.zstd');
    const whole = fs.readFileSync(file);
    fs.writeFileSync(file, whole.subarray(0, whole.length - 7));
  }

  const { listSessions } = await import('../src/sessions.js');
  const listed = await listSessions({ engine: 'dsh' });
  const byId = new Map(listed.map((s) => [s.id, s]));

  check('every fixture session is listed', listed.length === 3, `${listed.length} listed`);

  const titled = byId.get('session-with-a-question');
  check('the opening question becomes the title', titled?.title === '把通知做成可靠的，后台也要能收到',
    String(titled?.title));

  const silent = byId.get('session-that-has-not-spoken');
  check('a session that has not said anything has no title', silent?.title === null,
    String(silent?.title));

  const torn = byId.get('session-being-written');
  check('a session with a torn last frame is still listed', Boolean(torn), torn ? 'listed' : 'missing');
  check('and still gets its title from the frames that are intact',
    torn?.title === 'half a file', String(torn?.title));

  check('the working directory is decoded for each session',
    titled?.cwd === 'C:\\Users\\test\\termdesk', String(titled?.cwd));

  // The list is what the phone draws; an internal field leaking into it would be a second
  // schema nobody asked for, and `_stat` exists only to build the titles.
  check('no internal field is left on the entries',
    listed.every((s) => !('_stat' in s)), Object.keys(listed[0] ?? {}).join(','));
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
