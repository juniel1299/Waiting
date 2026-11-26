// 화면 오류 보고 판단 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { createReporter, reportPayload } from "../src/lib/errorReport.ts";

test("보고 내용은 메시지·경로(질의 없이)·배포 버전뿐이다", () => {
  const p = reportPayload(new TypeError("x is undefined"), "/sessions/3?token=abc#top", "abc123");
  assert.deepEqual(p, { message: "TypeError: x is undefined", path: "/sessions/3", release: "abc123" });
  assert.equal(reportPayload("문자열 오류", "/", undefined).message, "문자열 오류");
  assert.equal(reportPayload({ weird: true }, "/", "r").message, "알 수 없는 오류");
});

test("같은 오류는 한 번만, 한 화면 적재마다 최대 5건만 보낸다", () => {
  const sent = [];
  const report = createReporter((payload) => sent.push(payload), 5);
  report(new Error("a"), "/x", "r");
  report(new Error("a"), "/x", "r");
  for (let i = 0; i < 10; i++) report(new Error(`b${i}`), "/x", "r");
  assert.equal(sent.length, 5);
  assert.equal(sent.filter((p) => p.message === "Error: a").length, 1);
});
