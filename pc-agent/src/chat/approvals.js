/**
 * One "may I run this?" question on the phone — for every engine.
 *
 * Both kernels ask the same thing in their own words: Codex before a command or
 * a patch, ACP before a tool call. The product rule is that the person holding
 * the phone answers in ONE place, so this module turns either kernel's request
 * into the same approval request and translates the answer back into that
 * kernel's own vocabulary.
 *
 * It lives outside `chat.js` because the manager had grown to carry both engine
 * orchestration and approval translation; the two only meet at four call sites
 * (`approvalHandler`, `permission`, `resolveApproval`, and the broker's
 * `settled` event), which are passed in explicitly below instead of being
 * reached for through `this`.
 *
 * Nothing here decides *policy*: the broker owns deadlines, offline settlement
 * and the record of who answered what (`approvals.js`). This file only speaks
 * the two kernels' languages.
 */

import { OPTIONS } from '../approvals.js';
import { decisionFor } from '../kernels/codex.js';

/** The only option ids an engine's answer may be translated into. */
export const KNOWN_OPTIONS = [OPTIONS.ALLOW_ONCE, OPTIONS.ALLOW_ALWAYS, OPTIONS.DENY];

/** One line describing what Codex is asking for. */
export function describeCodexRequest(params) {
  const command = params?.command ?? params?.commandLine?.[0] ?? null;
  if (typeof command === 'string' && command.trim()) {
    const args = Array.isArray(params?.commandLine) ? params.commandLine.slice(1).join(' ') : '';
    return args ? `${command} ${args}` : command;
  }
  const changes = params?.changes ?? params?.fileChanges ?? null;
  if (Array.isArray(changes) && changes.length > 0) {
    return changes.map((c) => c?.path ?? c?.file ?? '').filter(Boolean).join(', ');
  }
  if (typeof params?.path === 'string') return params.path;
  return '（内核没有说明具体内容）';
}

/**
 * The approval-facing methods the manager mixes in.
 *
 * Dependencies are explicit because each one is a real seam:
 *   broker              — the single question queue (deadlines, offline, record)
 *   findChatByThread    — Codex names a thread, not a chat
 *   newestCodexChat     — fallback when a Codex request does not name its thread
 *   acpChatForSession   — ACP names a session id
 *   chatById            — which chat an approval note belongs to
 *   pushAndEmit         — write one line into that chat's transcript
 */
export function createApprovalHandlers({
  broker,
  findChatByThread,
  newestCodexChat,
  acpChatForSession,
  chatById,
  pushAndEmit,
}) {
  /**
   * Write the decision into the conversation it belongs to.
   *
   * Every settlement lands in the transcript — including a timeout or an
   * "offline" fallback — because "who allowed this?" is a question the user asks
   * afterwards, and a decision nobody can see is worse than no decision.
   */
  const pushApprovalNote = ({ request, optionId, by, note }) => {
    if (!note) return;
    const chat = request.chatId ? chatById(request.chatId) : null;
    if (!chat) return;
    // `note` already says who settled it and why (the broker writes that), so the
    // name stays a plain marker.
    pushAndEmit(chat, { kind: 'engine_note', role: 'engine', text: note, name: 'permission' });
  };

  return {
    /** Subscribe the note writer to the broker. Called once, from the manager. */
    watch() {
      broker.on('settled', (info) => pushApprovalNote(info));
    },

    /**
     * One Codex approval request -> one question on the phone.
     *
     * The reason is taken from the request itself (the command, or the patch), so
     * the phone shows what will actually run rather than "the agent wants
     * permission".
     */
    async answerCodexApproval(method, params) {
      const chat = findChatByThread(params?.threadId) ?? newestCodexChat();
      const detail = describeCodexRequest(params);
      const optionId = await broker.request({
        chatId: chat?.id ?? null,
        engine: 'codex',
        title: 'Codex 想要执行',
        detail,
        kind: /patch|filechange|file/i.test(method) ? 'file' : 'command',
        fallback: OPTIONS.DENY,
      }).catch(() => OPTIONS.DENY);
      return decisionFor(optionId);
    },

    /**
     * One ACP permission request -> one question on the phone.
     *
     * The kernel's own options are reused as the answer vocabulary when they match
     * ours, so "always allow" means what the kernel means by it.
     */
    async handleAcpPermission(engineId, info) {
      const chat = acpChatForSession(info?.sessionId);
      const options = Array.isArray(info.options) ? info.options : [];
      const ids = options
        .map((o) => o?.optionId ?? o?.id)
        .filter((id) => KNOWN_OPTIONS.includes(id));
      // A kernel that offers something we do not speak (a "cancel" kind, say)
      // still gets an answer: the vocabulary is ours, and anything unmapped is
      // simply not offered.
      const optionId = await broker.request({
        chatId: chat?.id ?? null,
        engine: engineId,
        title: info.toolCall?.title ? `内核想要执行 ${info.toolCall.title}` : '内核请求权限',
        // The kind rides along as its own field, so repeating it here would only
        // make the sentence longer without saying anything new.
        detail: info.toolCall?.title ? '' : (info.toolCall?.kind ?? ''),
        kind: info.toolCall?.kind === 'edit' || info.toolCall?.kind === 'write' ? 'file' : 'tool',
        ids: ids.length > 0 ? ids : null,
        fallback: KNOWN_OPTIONS.includes(info.defaultOptionId) ? info.defaultOptionId : OPTIONS.DENY,
      }).catch(() => OPTIONS.DENY);
      info.respond(optionId === OPTIONS.DENY ? null : optionId);
    },

    /**
     * The phone's answer.
     *
     * Rejected rather than trusted when the request is unknown or already settled:
     * an answer that is not currently pending must not be able to decide
     * something else.
     */
    resolveApproval({ requestId, optionId }) {
      if (!KNOWN_OPTIONS.includes(optionId)) {
        return { ok: false, code: 'bad_option', message: `不认识的选项：${optionId}` };
      }
      return broker.resolve({ requestId, optionId });
    },
  };
}
