/**
 * 会话分页：手机先要最新一页，往上拉再要更早的。
 *
 * 钉两件事：分页按 seq 切（事件会被修订、删除，下标会漂），以及 hasMore / oldest
 * 这两个字段足够手机判断"还能不能往上拉"和"下一页从哪要"。
 *
 *   node tools/chat-page-test.js
 */
import assert from 'node:assert/strict';

import { Chat } from '../src/chat/chat.js';

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

/** 只给 page() 需要的东西：事件表 + summary()。 */
function fakeChat(count) {
  const events = Array.from({ length: count }, (_, i) => ({ seq: i + 1, kind: 'message', text: `第 ${i + 1} 条` }));
  return { events, summary: () => ({ id: 'chat-1' }) };
}

const page = (count, opts) => Chat.prototype.page.call(fakeChat(count), opts);

check('最新一页取尾部，并告诉手机还有更早的', () => {
  const p = page(5, { limit: 2 });
  assert.deepEqual(p.events.map((e) => e.seq), [4, 5]);
  assert.equal(p.eventSync.hasMore, true);
  assert.equal(p.eventSync.oldest, 4);
  assert.equal(p.eventSync.total, 5);
});

check('before 是 seq，不是下标', () => {
  const mid = page(5, { before: 4, limit: 2 });
  assert.deepEqual(mid.events.map((e) => e.seq), [2, 3]);
  assert.equal(mid.eventSync.hasMore, true);
  assert.equal(mid.eventSync.oldest, 2);

  const first = page(5, { before: 2, limit: 2 });
  assert.deepEqual(first.events.map((e) => e.seq), [1]);
  assert.equal(first.eventSync.hasMore, false);
  assert.equal(first.eventSync.complete, true);
});

check('事件被删掉之后，分页不会跳过剩下的那些', () => {
  const fake = fakeChat(5);
  // 删掉 seq 2，就像一轮在别处被清理掉。
  fake.events = fake.events.filter((e) => e.seq !== 2);
  const p = Chat.prototype.page.call(fake, { before: 4, limit: 2 });
  assert.deepEqual(p.events.map((e) => e.seq), [1, 3]);
});

check('页大小有上限，避免一次把整段历史拉回来', () => {
  const p = page(500, { limit: 100000 });
  assert.equal(p.events.length, 200);
  assert.equal(p.eventSync.hasMore, true);
});

check('空会话给空页，不是报错', () => {
  const p = page(0, { limit: 10 });
  assert.deepEqual(p.events, []);
  assert.equal(p.eventSync.hasMore, false);
  assert.equal(p.eventSync.oldest, 0);
});

console.log(`\nChat page: ${passed} passed`);
