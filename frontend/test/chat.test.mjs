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
