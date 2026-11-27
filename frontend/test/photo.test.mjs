// 사진 올리기 계획·실패 안내 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { MAX_UPLOAD_BYTES, uploadFailureText, uploadPlan } from "../src/lib/photo.ts";

const MB = 1024 * 1024;

test("JPEG·PNG·WebP 만 받고, 4MB 넘으면 브라우저에서 줄여 보낸다(Vercel 프록시 요청 한도 4.5MB 보다 작게)", () => {
  assert.equal(MAX_UPLOAD_BYTES, 4 * MB);
  assert.deepEqual(uploadPlan({ type: "image/jpeg", size: 2 * MB, name: "a.jpg" }), { ok: true, shrink: false });
  assert.deepEqual(uploadPlan({ type: "image/webp", size: 4 * MB + 1, name: "a.webp" }), { ok: true, shrink: true });
  assert.equal(uploadPlan({ type: "image/gif", size: 10, name: "a.gif" }).ok, false);
  assert.equal(uploadPlan({ type: "image/png", size: 0, name: "a.png" }).ok, false);
  assert.match(uploadPlan({ type: "image/png", size: 40 * MB, name: "a.png" }).error, /너무 커요/);
});

test("HEIC 는 확장자·형식 어느 쪽으로 와도 지원 형식과 변환 방법을 안내한다", () => {
  for (const f of [{ type: "image/heic", name: "IMG_0001.HEIC" }, { type: "", name: "photo.heif" }, { type: "image/heif", name: "x" }]) {
    const plan = uploadPlan({ ...f, size: 3 * MB });
    assert.equal(plan.ok, false);
    assert.match(plan.error, /HEIC/);
    assert.match(plan.error, /높은 호환성/);
  }
});

test("실패 안내: 프록시 413(본문이 JSON 이 아님)·서버 형식 거부·연결 실패를 구분한다", () => {
  assert.match(uploadFailureText({ status: 413, code: "UNKNOWN", message: "" }), /4MB/);
  assert.equal(uploadFailureText({ status: 400, code: "PHOTO_CMYK_UNSUPPORTED", message: "인쇄용(CMYK) JPEG 는 받지 않아요." }), "인쇄용(CMYK) JPEG 는 받지 않아요.");
  assert.match(uploadFailureText({ status: 503, code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요." }), /다시 시도/);
  assert.match(uploadFailureText(new TypeError("Failed to fetch")), /다시 시도/);
});
