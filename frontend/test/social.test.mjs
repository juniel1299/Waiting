// 소셜 연결 결과 안내 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { PROVIDER_LABEL, socialNotice } from "../src/lib/social.ts";

test("콜백 결과(?linked= / ?error=)를 한국어 안내로 바꾼다", () => {
  assert.deepEqual(socialNotice(new URLSearchParams("linked=kakao")), { kind: "status", text: "카카오 계정을 연결했어요." });
  assert.equal(socialNotice(new URLSearchParams("error=SOCIAL_ALREADY_LINKED")).kind, "alert");
  assert.match(socialNotice(new URLSearchParams("error=SOCIAL_ALREADY_LINKED")).text, /다른 회원/);
  assert.match(socialNotice(new URLSearchParams("error=SOCIAL_NOT_LINKED")).text, /연결된 계정이 없/);
  assert.match(socialNotice(new URLSearchParams("error=SOMETHING_NEW")).text, /다시 시도/);
  assert.equal(socialNotice(new URLSearchParams("")), null);
  assert.equal(socialNotice(new URLSearchParams("linked=evil")), null);
  assert.equal(PROVIDER_LABEL.naver, "네이버");
});
