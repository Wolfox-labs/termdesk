/**
 * 内核的任务表要带着状态到手机上。
 *
 * 手机端把它画成「任务 · N 进行中 · M 待处理」并列出条目，所以 `todo/write` 折成
 * `engine_plan` 时必须保留每条的状态：`[x]` 完成、`[~]` 进行中、`[ ]` 待办。
 * 只留下光秃秃的条目文本，手机上就只剩一串行，看不出还剩多少。
 *
 *   node tools/todo-plan-test.js
 */
import assert from 'node:assert/strict';

import { dshEventToChatEvent } from '../src/sessions.js';

let passed = 0;
function check(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`PASS  ${name}`);
  } catch (err) {
    console.error(`FAIL  ${name}\n      ${err.message}`);
    process.exitCode = 1;
  }
}

check('三种状态各自带上记号', () => {
  const event = dshEventToChatEvent({
    type: 'todo/write',
    data: {
      todos: [
        { text: '读代码', status: 'completed' },
        { text: '改接口', status: 'in_progress' },
        { text: '补测试', status: 'pending' },
      ],
    },
  });
  assert.equal(event.kind, 'engine_plan');
  assert.equal(event.text, '[x] 读代码\n[~] 改接口\n[ ] 补测试');
  assert.equal(event.meta.items.length, 3);
  assert.equal(event.meta.items[1].status, 'in_progress');
});

check('也认 items 这个字段名和 done / running 这些写法', () => {
  const event = dshEventToChatEvent({
    type: 'todo/write',
    data: {
      items: [
        { content: '甲', status: 'done' },
        { content: '乙', status: 'running' },
        { content: '丙' },
      ],
    },
  });
  assert.equal(event.text, '[x] 甲\n[~] 乙\n[ ] 丙');
});

check('空任务表不出事件，免得手机上留一条空带子', () => {
  assert.equal(dshEventToChatEvent({ type: 'todo/write', data: { todos: [] } }), null);
  assert.equal(dshEventToChatEvent({ type: 'todo/write', data: {} }), null);
});

console.log(`\nTodo plan: ${passed} passed`);
