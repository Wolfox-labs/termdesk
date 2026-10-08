/**
 * The transcript rules, pinned without a kernel.
 *
 * Every one of these checks is a bug that actually shipped: a reply stored
 * twice, a preview removed only on this side, a reasoning block glued to an
 * answer, a switch that dropped the scrollback. They used to be reachable only
 * by spawning a real runtime; here they are ordinary functions.
 *
 *   node tools/chat-stream-test.js
 */
import { deltaKind, streamingTarget, handleAssistantChunk, discardPreviews } from '../src/chat/stream.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** The smallest thing that behaves like a conversation for these rules. */
function conversation() {
  const chat = { id: 'c1', events: [], previews: [], seq: 0 };
  chat.push = (event) => {
    chat.seq += 1;
    const record = { seq: chat.seq, at: 0, ...event };
    chat.events.push(record);
    return record;
  };
  return chat;
}

/** Records what the client was told, in order. */
function sink() {
  const frames = [];
  return {
    frames,
    push: (chat, event) => chat.push(event),
    emitEvent: (chat, record, extra = {}) => frames.push({ kind: 'event', seq: record.seq, text: record.text, ...extra }),
    emit: (payload) => frames.push({ kind: 'frame', ...payload }),
  };
}

const chunk = (type, text = '', index = 0) => ({ data: { chunk: { type, text, index } } });

// ---- what a delta means -----------------------------------------------------

check('a text delta is a message', deltaKind('text-delta') === 'message');
check('a reasoning delta is reasoning', deltaKind('reasoning-delta') === 'reasoning');
check('block boundaries carry no content', deltaKind('block-start') === null && deltaKind('block-end') === null);
check('an unknown chunk type is ignored rather than guessed', deltaKind('something-new') === null);

// ---- coalescing: one growing line, not one line per token -------------------

{
  const chat = conversation();
  const s = sink();
  handleAssistantChunk(chat, chunk('block-start'), s);
  for (const t of ['He', 'llo', ' world']) handleAssistantChunk(chat, chunk('text-delta', t), s);

  check('deltas coalesce into ONE record', chat.events.length === 1, `events=${chat.events.length}`);
  check('and the record holds the whole text', chat.events[0].text === 'Hello world', chat.events[0].text);
  check('every delta told the client it was a replacement', s.frames.every((f) => f.stream === true));
  check('all frames address the same seq', new Set(s.frames.map((f) => f.seq)).size === 1);
}

// ---- the finished message replaces the preview, it does not join it ---------

{
  const chat = conversation();
  const s = sink();
  handleAssistantChunk(chat, chunk('text-delta', 'partial'), s);
  const previewSeq = chat.events[0].seq;
  // the kernel now sends the authoritative message
  chat.push({ kind: 'message', role: 'assistant', text: 'partial answer' });
  const removed = discardPreviews(chat, 'message', s);

  check('the preview is dropped once the real message arrives', removed === 1, `removed=${removed}`);
  check('the transcript keeps exactly the durable one', chat.events.length === 1 && chat.events[0].text === 'partial answer');
  check('previews no longer hold it', chat.previews.length === 0);
  const gone = s.frames.filter((f) => f.removed === true);
  check('the client is told the old seq is gone', gone.length === 1 && gone[0].seq === previewSeq);
}

// ---- a new block does not glue itself onto the previous one ----------------

{
  const chat = conversation();
  const s = sink();
  handleAssistantChunk(chat, chunk('reasoning-delta', 'thinking', 0), s);
  handleAssistantChunk(chat, chunk('text-delta', 'answer', 1), s);

  check('reasoning and answer are separate records', chat.events.length === 2, `events=${chat.events.length}`);
  check('the reasoning record is reasoning', chat.events[0].kind === 'reasoning' && chat.events[0].text === 'thinking');
  check('the answer record is a message', chat.events[1].kind === 'message' && chat.events[1].text === 'answer');
  check('neither record absorbed the other\'s text',
    chat.events[0].text === 'thinking' && chat.events[1].text === 'answer',
    chat.events.map((e) => `${e.kind}:${e.text}`).join(' | '));
}

// ---- an interleaved record closes the preview ------------------------------

{
  const chat = conversation();
  const s = sink();
  handleAssistantChunk(chat, chunk('text-delta', 'first'), s);
  chat.push({ kind: 'tool', role: 'engine', text: 'ran a command' });  // not a preview
  handleAssistantChunk(chat, chunk('text-delta', 'second'), s);

  const streamed = chat.events.filter((e) => e.streaming === true);
  check('a delta after another record starts a NEW record', streamed.length === 2, `streaming=${streamed.length}`);
  check('the interrupted preview keeps its own text', streamed[0]?.text === 'first', streamed[0]?.text);
  check('and the new delta opens a fresh one', streamed[1]?.text === 'second', streamed[1]?.text);
  // The tool record still sits between them: the interrupted preview is closed.
  check('the interrupted preview is closed, not reused',
    streamingTarget({ ...chat, previews: [streamed[0]] }, 'message').append === false);
}

// ---- block-end closes the cursor without dropping the record ---------------

{
  const chat = conversation();
  const s = sink();
  handleAssistantChunk(chat, chunk('block-start'), s);
  handleAssistantChunk(chat, chunk('text-delta', 'done'), s);
  handleAssistantChunk(chat, chunk('block-end'), s);
  check('block-end clears the streaming cursor', chat.streaming === null);
  check('but keeps the preview registered for the authoritative message', chat.previews.length === 1);
}

// ---- nothing to discard is not an error ------------------------------------

{
  const chat = conversation();
  const s = sink();
  check('discarding with no previews returns zero', discardPreviews(chat, 'message', s) === 0);
  check('and tells the client nothing', s.frames.length === 0);
}

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);
