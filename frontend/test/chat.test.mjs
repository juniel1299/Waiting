// 모임 채팅 이어 받기 합치기 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { mergeMessages } from "../src/lib/chat.ts";

const m = (id, body, deleted = false) => ({ id, body: deleted ? null : body, deleted });

test("새 메시지는 id 순서로 붙고, 같은 id 는 새 상태(삭제 표시)로 바뀌며 원본은 그대로다", () => {
  const before = [m(1, "a"), m(3, "c")];
  const after = mergeMessages(before, [m(4, "d"), m(2, "b"), m(1, null, true)]);
  assert.deepEqual(after.map((x) => x.id), [1, 2, 3, 4]);
  assert.equal(after[0].deleted, true);
  assert.equal(after[0].body, null);
  assert.equal(before[0].deleted, false);
});

test("변경이 없으면 같은 배열을 돌려준다(다시 그리지 않게)", () => {
  const list = [m(1, "a")];
  assert.equal(mergeMessages(list, []), list);
});

import { anchorScrollTop, appendedCount, isNearBottom } from "../src/lib/chat.ts";

test("이전 기록을 위에 붙이면 늘어난 높이만큼 내려 보던 메시지를 같은 자리에 둔다", () => {
  assert.equal(anchorScrollTop({ scrollHeight: 2000, scrollTop: 0 }, 3500), 1500);
  assert.equal(anchorScrollTop({ scrollHeight: 2000, scrollTop: 40 }, 2600), 640);
});

test("맨 아래 근처(120px 이내)인지", () => {
  assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 600, clientHeight: 400 }), true);
  assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 500, clientHeight: 400 }), true);
  assert.equal(isNearBottom({ scrollHeight: 1000, scrollTop: 300, clientHeight: 400 }), false);
});

test("새로 아래에 붙은 메시지 수: 이전 기록(더 작은 id)·내 메시지·이미 있던 것은 세지 않는다", () => {
  const before = [m(10, "a"), m(11, "b")];
  const after = [m(5, "old"), ...before, { ...m(12, "c") }, { ...m(13, "mine"), mine: true }, m(14, "d")];
  assert.equal(appendedCount(before, after), 2);
  assert.equal(appendedCount([], after), 0);
  assert.equal(appendedCount(before, before), 0);
});
