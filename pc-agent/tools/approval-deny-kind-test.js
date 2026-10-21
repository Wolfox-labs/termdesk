import assert from 'node:assert/strict';
import test from 'node:test';
import { createApprovalHandlers } from '../src/chat/approvals.js';

test('a generic deny selects reject_once even when reject_always is offered first', async () => {
  let response;
  const handlers = createApprovalHandlers({
    broker: { request: async () => 'deny' },
    acpChatForSession: () => null,
  });
  await handlers.handleAcpPermission('fixture', {
    options: [
      { kind: 'reject_always', optionId: 'reject-all' },
      { kind: 'reject_once', optionId: 'reject-this-call' },
    ],
    respond: value => { response = value; },
  });
  assert.equal(response, 'reject-this-call');
});
