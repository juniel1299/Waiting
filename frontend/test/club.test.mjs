// 모임 진행 단계·수정 안내 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { editHint, modeLabel, STATUS_LABELS, stageHint } from "../src/lib/club.ts";

test("모든 진행 단계는 글자 라벨과 안내를 가진다", () => {
  for (const status of ["PENDING", "APPROVED", "REJECTED", "CANCELLED"]) {
    assert.ok(STATUS_LABELS[status].length > 0);
    assert.ok(stageHint(status).length > 0);
  }
  assert.equal(STATUS_LABELS.REJECTED, "반려됨");
});

test("수정 안내는 승인 모임은 바로 반영, 반려·취소는 재신청이라고 알린다", () => {
  assert.match(editHint("APPROVED"), /바로 반영/);
  assert.match(editHint("REJECTED"), /다시 승인/);
  assert.match(editHint("CANCELLED"), /다시 승인/);
});

test("활동 방식 라벨", () => {
  assert.equal(modeLabel("MIXED"), "온·오프라인");
});
