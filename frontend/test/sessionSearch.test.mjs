// 회차 검색 조건 ↔ 주소 변환 단위 검증. 실행: npm test (Node 의 TS 타입 제거로 sessionSearch.ts 를 바로 불러온다)
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  DEFAULT_SEARCH,
  apiQuery,
  pageWindow,
  parseSearch,
  searchHref,
  withChange,
} from "../src/lib/sessionSearch.ts";

const params = (s) => new URLSearchParams(s);

test("빈 주소는 기본 조건: 시작 전 회차, 시작 임박순, 1쪽", () => {
  assert.deepEqual(parseSearch(params("")), DEFAULT_SEARCH);
  assert.equal(DEFAULT_SEARCH.status, "UPCOMING");
  assert.equal(DEFAULT_SEARCH.sort, "SOON");
  assert.equal(DEFAULT_SEARCH.page, 1);
});

test("주소의 조건을 읽고, 모르는 값·틀린 쪽 번호는 기본값으로 둔다", () => {
  assert.deepEqual(parseSearch(params("q=%20스프링%20&field=TITLE&from=2031-03-10&to=2031-03-12&status=OPEN&sort=SEATS&page=3")), {
    q: "스프링",
    field: "TITLE",
    from: "2031-03-10",
    to: "2031-03-12",
    status: "OPEN",
    sort: "SEATS",
    page: 3,
  });
  assert.deepEqual(parseSearch(params("field=X&status=Y&sort=Z&page=-1&from=2031-1-1")), DEFAULT_SEARCH);
  assert.equal(parseSearch(params("page=2.5")).page, 1);
});

test("주소에는 기본값이 아닌 조건만 남긴다", () => {
  assert.equal(searchHref("/sessions", DEFAULT_SEARCH), "/sessions");
  const state = { ...DEFAULT_SEARCH, q: "리액트 스터디", status: "ALL", page: 2 };
  assert.equal(searchHref("/me/sessions", state), "/me/sessions?q=%EB%A6%AC%EC%95%A1%ED%8A%B8+%EC%8A%A4%ED%84%B0%EB%94%94&status=ALL&page=2");
  assert.deepEqual(parseSearch(new URL(searchHref("/x", state), "http://h").searchParams), state);
});

test("조건이 바뀌면 1쪽으로, 쪽만 바꾸면 그 쪽으로", () => {
  const onPage3 = { ...DEFAULT_SEARCH, page: 3 };
  assert.equal(withChange(onPage3, { sort: "TITLE" }).page, 1);
  assert.equal(withChange(onPage3, { q: "x" }).page, 1);
  assert.equal(withChange(onPage3, { page: 4 }).page, 4);
  assert.equal(onPage3.page, 3, "원래 조건은 바꾸지 않는다");
});

test("API 질의에는 범위와 모든 조건을 담는다", () => {
  assert.equal(
    apiQuery("mine", { ...DEFAULT_SEARCH, q: "a b", from: "2031-03-10" }),
    "scope=mine&q=a+b&field=ALL&from=2031-03-10&status=UPCOMING&sort=SOON&page=1&size=10",
  );
});

test("쪽 번호는 현재 쪽 주변 최대 5개", () => {
  assert.deepEqual(pageWindow(1, 1), [1]);
  assert.deepEqual(pageWindow(1, 9), [1, 2, 3, 4, 5]);
  assert.deepEqual(pageWindow(5, 9), [3, 4, 5, 6, 7]);
  assert.deepEqual(pageWindow(9, 9), [5, 6, 7, 8, 9]);
  assert.deepEqual(pageWindow(1, 0), []);
});
