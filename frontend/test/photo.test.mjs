// 사진 올리기 계획 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { uploadPlan } from "../src/lib/photo.ts";

const MB = 1024 * 1024;

test("JPEG·PNG·WebP 만 받고, 4MB 넘으면 브라우저에서 줄여 보낸다(프록시 요청 한도 4.5MB)", () => {
  assert.deepEqual(uploadPlan({ type: "image/jpeg", size: 2 * MB }), { ok: true, shrink: false });
  assert.deepEqual(uploadPlan({ type: "image/webp", size: 4 * MB + 1 }), { ok: true, shrink: true });
  assert.equal(uploadPlan({ type: "image/gif", size: 10 }).ok, false);
  assert.equal(uploadPlan({ type: "", size: 10 }).ok, false);
  assert.match(uploadPlan({ type: "image/heic", size: 10 }).error, /JPEG·PNG·WebP/);
  assert.equal(uploadPlan({ type: "image/png", size: 0 }).ok, false);
  assert.match(uploadPlan({ type: "image/png", size: 40 * MB }).error, /너무 커요/);
});
