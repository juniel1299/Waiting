// 게시판 주소·종류 단위 검증 (모임 게시판 포함). 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { boardInfo, canWriteBoard, kindOf } from "../src/lib/board.ts";

test("모임 게시판·모임 공지는 모임 주소 아래 화면과 API 를 쓴다", () => {
  assert.equal(boardInfo("club-board", 7).path, "/clubs/7/board");
  assert.equal(boardInfo("club-notice", 7).api, "/api/clubs/7/boards/notice/posts");
  assert.equal(boardInfo("community").api, "/api/boards/community/posts");
});

test("응답의 board 값을 화면 종류로 바꾼다", () => {
  assert.equal(kindOf("CLUB_NOTICE"), "club-notice");
  assert.equal(kindOf("NOTICE"), "notice");
});

test("데모는 어디에도 쓰지 못하고, 모임 게시판 쓰기는 서버가 가입·역할로 판단한다", () => {
  const demo = { id: 1, loginId: "d", displayName: "d", role: "PARTICIPANT", demo: true };
  const member = { ...demo, demo: false };
  assert.equal(canWriteBoard("club-board", demo), false);
  assert.equal(canWriteBoard("club-notice", member), true);
  assert.equal(canWriteBoard("notice", member), false);
});
