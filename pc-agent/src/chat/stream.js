/**
 * The transcript rules: what is durable, what is a preview, and what the phone
 * is told when the two disagree.
 *
 * Three kernels stream into the same conversation shape, and each one had its
 * own way of getting this wrong at least once:
 *
 *   - deltas arrived one frame per token, so the phone re-rendered constantly;
 *   - the finished `assistant/message` was appended next to the streamed preview
 *     of the very same answer, so every reply appeared twice;
 *   - a preview was removed locally but the client was never told, so its
 *     incremental view kept the duplicate forever;
 *   - a delta for a *different* block was coalesced into the previous one,
 *     gluing a reasoning block and an answer together.
 *
 * Those are all rules about the transcript, not about any kernel: they were
 * spread through `ChatManager` and only reachable through a live runtime. Here
 * they are functions over a conversation, so `tools/chat-stream-test.js` can
 * pin each of the four cases above without spawning anything.
 *
 * The sink is the small surface this module needs from whoever owns the
 * conversation (`Chat` provides `push`, the manager provides `emitEvent`). Side
 * effects stay on that side on purpose: this file decides *what* happens to the
 * transcript, never opens a socket.
 */

/**
 * Which transcript kind a streamed delta produces, or null when the chunk is not
 * a content delta (block boundaries and unknown chunk types carry no text).
 */
export function deltaKind(chunkType) {
  if (chunkType === 'text-delta') return 'message';
  if (chunkType === 'reasoning-delta') return 'reasoning';
  return null;
}

/**
 * Where one delta should land: append to an open preview, or start a new record.
 *
 * The check is "the last event in the log IS the open preview of this kind", not
 * "some preview of this kind exists". A later record (the user's next message,
 * a tool call) in between means the previous preview is closed for good.
 */
export function streamingTarget(chat, kind) {
  const openPreview = chat.previews.filter((p) => p.kind === kind).pop();
  if (!openPreview) return { append: false };
  const last = chat.events[chat.events.length - 1];
  return { append: last === openPreview, record: openPreview };
}

/**
 * One streamed chunk from the kernel's `assistant/chunk` events.
 *
 * Block boundaries only move the streaming cursor; deltas are coalesced into one
 * growing event (see the header note), and the same record is re-emitted with
 * `stream: true` so the phone replaces its copy instead of adding a line.
 */
export function handleAssistantChunk(chat, ev, sink) {
  const chunk = ev.data?.chunk ?? {};
  const index = chunk.index ?? 0;

  if (chunk.type === 'block-start') {
    chat.streaming = { index, blockType: chunk.blockType ?? 'text', text: '' };
    return;
  }
  if (chunk.type === 'block-end') {
    // Close the preview, but keep it registered: the authoritative
    // `assistant/message` may still replace it later in the turn.
    if (chat.streaming) chat.streaming.open = false;
    chat.streaming = null;
    return;
  }

  const kind = deltaKind(chunk.type);
  if (!kind) return;

  const text = chunk.text ?? '';
  if (!chat.streaming || chat.streaming.index !== index) {
    chat.streaming = { index, blockType: kind === 'reasoning' ? 'reasoning' : 'text', text: '' };
  }
  chat.streaming.text += text;

  const target = streamingTarget(chat, kind);
  if (target.append) {
    target.record.text += text;
    sink.emitEvent(chat, target.record, { stream: true });
    return;
  }

  const record = sink.push(chat, { kind, role: 'assistant', text, streaming: true });
  chat.previews.push(record);
  sink.emitEvent(chat, record, { stream: true });
}

/**
 * Drop the streamed previews of one kind once the kernel sent the finished
 * message.
 *
 * The runtime emits `assistant/chunk` deltas for live display and then one
 * `assistant/message` holding the finished blocks. Only the finished message is
 * durable, so the previews of that same kind are removed to keep the transcript
 * equal to what the runtime actually recorded.
 *
 * The client already learned those seqs, so each removal is announced — an
 * incremental view that is never told cannot drop the duplicate.
 *
 * @returns {number} how many preview events were removed.
 */
export function discardPreviews(chat, kind, sink) {
  const doomed = new Set(chat.previews.filter((p) => p.kind === kind));
  if (doomed.size === 0) return 0;
  chat.events = chat.events.filter((e) => !doomed.has(e));
  chat.previews = chat.previews.filter((p) => !doomed.has(p));
  for (const preview of doomed) {
    sink.emit({ event: 'chat.event', chatId: chat.id, seq: preview.seq, removed: true });
  }
  return doomed.size;
}
