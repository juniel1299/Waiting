// 게시판 검색 조건 ↔ 주소 변환 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { BOARD_DEFAULT, boardApiQuery, boardHref, parseBoardSearch } from "../src/lib/boardSearch.ts";

test("빈 주소는 전체 범위·1쪽", () => {
  assert.deepEqual(parseBoardSearch(new URLSearchParams("")), BOARD_DEFAULT);
});

test("주소의 검색어·범위·쪽을 읽고 모르는 값은 기본값", () => {
  assert.deepEqual(parseBoardSearch(new URLSearchParams("q=%20스프링%20&field=BODY&page=3")), { q: "스프링", field: "BODY", page: 3 });
  assert.deepEqual(parseBoardSearch(new URLSearchParams("field=X&page=-2")), BOARD_DEFAULT);
});

test("주소에는 기본값이 아닌 조건만, API 질의에는 모두", () => {
  assert.equal(boardHref("/board", BOARD_DEFAULT), "/board");
  assert.equal(boardHref("/notices", { q: "공지", field: "TITLE", page: 2 }), "/notices?q=%EA%B3%B5%EC%A7%80&field=TITLE&page=2");
  assert.equal(boardApiQuery({ q: "a b", field: "ALL", page: 1 }), "q=a+b&field=ALL&page=1&size=20");
});
