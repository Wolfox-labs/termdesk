/**
 * A stub ACP agent, for testing TermDesk's side of the pipe without spending
 * anything on a model.
 *
 * Why it exists: the real end-to-end question ("does the phone show streamed
 * text, tool calls and a permission prompt?") used to require a paid model call,
 * and the free tiers are not usable from a third-party client — OpenCode refuses
 * it outright ("free tier can only be used from within OpenCode"). This stub
 * speaks the same protocol the real kernels do, so everything TermDesk owns can
 * be exercised for free and repeatably.
 *
 * It behaves like a well-behaved kernel: initialize declares session
 * capabilities, session/new returns a model list, session/prompt streams a
 * message piece by piece, runs one tool call, asks permission, and only then
 * concludes the turn.
 *
 * Registered through the environment, so no production code knows it exists:
 *   TERMDESK_ACP_KERNELS='[{"id":"stub","label":"Stub","bin":"node","args":["tools/fake-acp-agent.mjs"]}]'
 */
const write = (value) => process.stdout.write(JSON.stringify(value) + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let sessionCounter = 0;
const sessions = new Map();
/**
 * requestId -> { resolve, unwrap, timer } for questions awaiting an answer.
 *
 * Two kinds of question go through here: `session/request_permission` (which
 * answers with an option id) and the terminal calls (which answer with a result
 * object). `unwrap` is what turns a JSON-RPC response into what the caller
 * wanted, so one mechanism serves both.
 *
 * The deadline is generous on purpose: this stub is driven by hand during
 * interactive checks, and a two-minute window is the difference between testing
 * the answer path and testing how fast a human can tap.
 */
const waiting = new Map();

process.stdin.setEncoding('utf8');
let buffer = '';
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    handle(message);
  }
});

const update = (sessionId, body) => write({ jsonrpc: '2.0', method: 'session/update', params: { sessionId, update: body } });

function handle(message) {
  // A response to a request we made, not a request of our own.
  if (message.id !== undefined && message.method === undefined) {
    const pending = waiting.get(message.id);
    if (pending) {
      waiting.delete(message.id);
      clearTimeout(pending.timer);
      pending.resolve(pending.unwrap ? pending.unwrap(message) : (message.result ?? null));
    }
    return;
  }

  switch (message.method) {
    case 'initialize':
      write({
        jsonrpc: '2.0',
        id: message.id,
        result: {
          protocolVersion: 1,
          agentInfo: { name: 'TermDesk Stub Kernel', version: '0.0.1' },
          agentCapabilities: {
            loadSession: true,
            promptCapabilities: { image: false, embeddedContext: false },
            sessionCapabilities: { list: {}, resume: {}, fork: {} },
          },
          authMethods: [],
        },
      });
      return;

    case 'authenticate':
      write({ jsonrpc: '2.0', id: message.id, result: null });
      return;

    case 'session/list':
      write({
        jsonrpc: '2.0',
        id: message.id,
        result: {
          sessions: [...sessions.values()].map((s) => ({
            sessionId: s.id,
            cwd: s.cwd,
            title: s.title,
            updatedAt: new Date(s.updatedAt).toISOString(),
          })),
        },
      });
      return;

    case 'session/new': {
      sessionCounter += 1;
      const id = `stub-${String(sessionCounter).padStart(4, '0')}`;
      const session = {
        id,
        cwd: message.params?.cwd ?? process.cwd(),
        title: `Stub session ${sessionCounter}`,
        updatedAt: Date.now(),
        model: 'stub/echo-1',
        messages: [],
        cancelled: false,
      };
      sessions.set(id, session);
      write({
        jsonrpc: '2.0',
        id: message.id,
        result: {
          sessionId: id,
          models: { currentModelId: session.model, availableModels: [{ modelId: 'stub/echo-1', name: 'Stub Echo' }] },
          modes: { currentModeId: 'build', availableModes: [{ id: 'build', name: 'build' }] },
          configOptions: [
            {
              id: 'model',
              name: 'Model',
              category: 'model',
              type: 'select',
              currentValue: session.model,
              options: [{ value: 'stub/echo-1', name: 'Stub Echo' }],
            },
          ],
        },
      });
      return;
    }

    case 'session/set_model': {
      const session = sessions.get(message.params?.sessionId);
      if (session) session.model = message.params?.modelId ?? session.model;
      write({ jsonrpc: '2.0', id: message.id, result: { _meta: { stub: { modelId: session?.model ?? null } } } });
      return;
    }

    case 'session/load': {
      const session = sessions.get(message.params?.sessionId);
      if (!session) {
        write({ jsonrpc: '2.0', id: message.id, error: { code: -32602, message: 'unknown session' } });
        return;
      }
      // Replay, the way a real kernel does when it is asked to reopen a session.
      for (const item of session.messages) update(session.id, item);
      write({ jsonrpc: '2.0', id: message.id, result: null });
      return;
    }

    case 'session/prompt': {
      const session = sessions.get(message.params?.sessionId);
      if (!session) {
        write({ jsonrpc: '2.0', id: message.id, error: { code: -32602, message: 'unknown session' } });
        return;
      }
      const text = (message.params?.prompt ?? []).map((p) => p?.text ?? '').join(' ');
      const user = { sessionUpdate: 'user_message_chunk', content: { type: 'text', text } };
      session.messages.push(user);
      update(session.id, user);
      session.cancelled = false;
      void runTurn(session, message.id, text);
      return;
    }

    case 'session/cancel': {
      const session = sessions.get(message.params?.sessionId);
      if (session) session.cancelled = true;
      return;
    }

    default:
      // Answer politely rather than hanging the client.
      if (message.id !== undefined) write({ jsonrpc: '2.0', id: message.id, result: null });
  }
}

async function runTurn(session, promptId, text) {
  const answer = `Stub kernel received: "${text}". It will run one command so the phone shows a tool call.`;
  for (const piece of answer.match(/.{1,18}/g) ?? []) {
    if (session.cancelled) break;
    await sleep(120);
    const chunk = { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: piece } };
    session.messages.push(chunk);
    update(session.id, chunk);
  }

  const toolCallId = `call-${Date.now()}`;
  const call = { sessionUpdate: 'tool_call', toolCallId, title: 'bash: echo stub', kind: 'execute', status: 'in_progress' };
  session.messages.push(call);
  update(session.id, call);
  await sleep(150);

  const option = await askPermission(session, toolCallId);
  const verdict = option ? `allowed (${option})` : 'denied';

  // A real kernel runs its execute tool in a terminal it asks the client for —
  // that is the whole reason the phone can see a conversation's command lines.
  // "long-terminal" asks for one that keeps running, so the write path can be
  // exercised too (the phone typing into it).
  let terminal = null;
  let note = 'stub output';
  if (option) {
    const wantsLiveTerminal = /long-terminal/i.test(text);
    const created = await callClient('terminal/create', {
      sessionId: session.id,
      command: process.execPath,
      args: wantsLiveTerminal
        ? ['-e', "process.stdin.on('data', (d) => process.stdout.write('echo:' + d.toString().trim() + '\\n'));"]
        : ['-e', "process.stdout.write('stub terminal says hello\\n')"],
      cwd: session.cwd,
      outputByteLimit: 32768,
    });
    terminal = created?.terminalId ?? null;
    if (terminal) {
      if (!wantsLiveTerminal) {
        const exit = await callClient('terminal/wait_for_exit', { sessionId: session.id, terminalId: terminal });
        const out = await callClient('terminal/output', { sessionId: session.id, terminalId: terminal });
        note = `exit ${exit?.exitCode} · ${String(out?.output ?? '').trim()}`;
      } else {
        note = `live terminal ${terminal}`;
      }
    }
  }

  const result = {
    sessionUpdate: 'tool_call_update',
    toolCallId,
    status: option ? 'completed' : 'failed',
    content: [
      { type: 'content', content: { type: 'text', text: `echo stub → ${note} (${verdict})` } },
      ...(terminal ? [{ type: 'terminal', terminalId: terminal }] : []),
    ],
  };
  session.messages.push(result);
  update(session.id, result);

  const tail = { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `\n\nDone. The command was ${verdict}.` } };
  session.messages.push(tail);
  update(session.id, tail);

  session.updatedAt = Date.now();
  write({ jsonrpc: '2.0', id: promptId, result: { stopReason: 'end_turn' } });
}

/**
 * Ask the client for something and wait for its answer.
 *
 * Failures come back as a thrown error carrying the JSON-RPC message, because a
 * stub that silently swallows "the client said no" would make a broken client
 * look like a working one.
 */
function callClient(method, params, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    const requestId = 9000 + Math.floor(Math.random() * 1000);
    const timer = setTimeout(() => {
      waiting.delete(requestId);
      reject(new Error(`${method} timed out`));
    }, timeoutMs);
    waiting.set(requestId, {
      timer,
      resolve: (result) => resolve(result),
      unwrap: (message) => {
        if (message.error) throw new Error(`${method}: ${message.error.message ?? 'error'}`);
        return message.result ?? null;
      },
    });
    write({ jsonrpc: '2.0', id: requestId, method, params });
  });
}

/** Ask the client the way a real kernel does, and wait for exactly one answer. */
function askPermission(session, toolCallId) {
  return new Promise((resolve) => {
    const requestId = 9000 + Math.floor(Math.random() * 1000);
    const timer = setTimeout(() => {
      waiting.delete(requestId);
      resolve(null);
    }, 120_000);
    waiting.set(requestId, {
      timer,
      resolve,
      // A permission answer is the option id, not the raw result object.
      unwrap: (message) => {
        const outcome = message.result?.outcome;
        return outcome?.outcome === 'selected' ? outcome.optionId : null;
      },
    });
    write({
      jsonrpc: '2.0',
      id: requestId,
      method: 'session/request_permission',
      params: {
        sessionId: session.id,
        toolCall: { toolCallId, title: 'bash: echo stub', kind: 'execute' },
        options: [
          { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' },
          { optionId: 'allow_always', name: 'Always allow', kind: 'allow_always' },
          { optionId: 'reject_once', name: 'Reject', kind: 'reject_once' },
        ],
      },
    });
  });
}