// 내 신청 상태 변화 안내 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { describeChange } from "../src/lib/reservationChange.ts";

const waiting = (n) => ({ status: "WAITING", waitingPosition: n });
const confirmed = { status: "CONFIRMED", waitingPosition: null };

test("대기에서 확정으로 바뀌면 확정 안내", () => {
  assert.equal(describeChange(waiting(1), confirmed), "대기 순번이 되어 예약이 확정됐어요.");
});

test("대기 순번이 앞당겨지면 이전·새 순번을 알린다", () => {
  assert.equal(describeChange(waiting(3), waiting(1)), "대기 순번이 3번에서 1번으로 앞당겨졌어요.");
});

test("처음 보는 상태, 그대로인 상태, 본인 신청·취소로 바뀐 상태는 알리지 않는다", () => {
  assert.equal(describeChange(null, waiting(2)), null);
  assert.equal(describeChange(waiting(2), waiting(2)), null);
  assert.equal(describeChange(confirmed, confirmed), null);
  assert.equal(describeChange(waiting(1), null), null);
  assert.equal(describeChange(null, confirmed), null);
  assert.equal(describeChange(waiting(1), waiting(2)), null);
});
