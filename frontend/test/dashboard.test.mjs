// 대시보드 차트 계산 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { chartSummary, daysBetween, dailySeries, linePoints, niceMax } from "../src/lib/dashboard.ts";

test("기간의 모든 날짜를 빠짐없이(종료일 포함) 만든다", () => {
  assert.deepEqual(daysBetween("2026-11-29", "2026-12-02"), ["2026-11-29", "2026-11-30", "2026-12-01", "2026-12-02"]);
  assert.deepEqual(daysBetween("2026-11-01", "2026-11-01"), ["2026-11-01"]);
});

test("일별 값은 기록이 없는 날을 0으로 채우고 일반·데모를 나눈다", () => {
  const usage = {
    from: "2026-11-01",
    to: "2026-11-03",
    daily: [
      { day: "2026-11-01", metric: "SIGNUP", regular: 2, demo: 0 },
      { day: "2026-11-03", metric: "SIGNUP", regular: 1, demo: 4 },
      { day: "2026-11-03", metric: "DEMO_STARTED", regular: 0, demo: 9 },
    ],
  };
  assert.deepEqual(dailySeries(usage, "SIGNUP"), [
    { day: "2026-11-01", regular: 2, demo: 0 },
    { day: "2026-11-02", regular: 0, demo: 0 },
    { day: "2026-11-03", regular: 1, demo: 4 },
  ]);
});

test("축 최댓값은 보기 좋은 수로 올리고, 모두 0이면 1", () => {
  assert.equal(niceMax([0, 0]), 1);
  assert.equal(niceMax([3]), 4);
  assert.equal(niceMax([7]), 8);
  assert.equal(niceMax([12]), 15);
  assert.equal(niceMax([96]), 100);
});

test("선 좌표는 안쪽 너비·높이 안에 있고 0은 바닥, 최댓값은 위", () => {
  const pts = linePoints([0, 5, 10], 200, 100, 10);
  assert.deepEqual(pts[0], [0, 100]);
  assert.deepEqual(pts[2], [200, 0]);
  assert.equal(pts[1][1], 50);
  assert.deepEqual(linePoints([3], 200, 100, 4), [[100, 25]]);
});

test("차트 요약 문장은 합계·가장 많은 날을 글자로 알린다(스크린 리더용)", () => {
  const s = chartSummary("가입", [
    { day: "2026-11-01", regular: 2, demo: 0 },
    { day: "2026-11-02", regular: 5, demo: 1 },
  ]);
  assert.equal(s, "가입 일별 추이, 2026-11-01~2026-11-02. 일반 합계 7, 가장 많은 날 2026-11-02 5. 데모 체험 합계 1.");
  assert.equal(chartSummary("가입", []), "가입 일별 추이. 기록 없음.");
});
