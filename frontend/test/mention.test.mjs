// 멘션 토큰 해석·삽입 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { insertMention, mentionQueryAt, splitMentions } from "../src/lib/mention.ts";

test("본문을 글자 조각과 멘션 조각으로 나누고, 이름은 현재 닉네임으로 바꾼다", () => {
  const names = new Map([[7, "새별"]]);
  assert.deepEqual(splitMentions("안녕 @[예전#7] 와 @[없음#9]!", names), [
    { text: "안녕 " },
    { mention: 7, name: "새별" },
    { text: " 와 @[없음#9]!" },
  ]);
  assert.deepEqual(splitMentions("<b>그냥 글</b>", names), [{ text: "<b>그냥 글</b>" }]);
});

test("커서 앞의 @단어를 찾아 후보 검색어로 쓴다", () => {
  assert.deepEqual(mentionQueryAt("안녕 @별", 5), { query: "별", start: 3 });
  assert.equal(mentionQueryAt("메일 a@b", 6), null);
  assert.equal(mentionQueryAt("안녕 @", 4), null);
});

test("고른 사람을 토큰으로 넣고 커서를 뒤로 옮긴다", () => {
  assert.deepEqual(insertMention("안녕 @별 반가워", 3, 5, { id: 7, nickname: "새별" }), {
    text: "안녕 @[새별#7]  반가워",
    cursor: 11,
  });
});
