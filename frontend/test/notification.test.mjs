// 알림 문구·머리글 버튼 이름 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { notificationText, unreadLabel } from "../src/lib/notification.ts";

const base = { id: 1, read: false, createdAt: "2026-11-23T00:00:00Z", actor: null, sessionTitle: null, postTitle: null, onComment: false, clubName: null };

test("승격 알림은 회차 제목으로 확정을 알린다", () => {
  assert.equal(notificationText({ ...base, type: "PROMOTED", sessionTitle: "알고리즘" }), "대기하던 ‘알고리즘’ 회차가 확정됐어요.");
});

test("멘션 알림은 멘션한 사람·글 제목·글인지 댓글인지 알린다", () => {
  const mention = { ...base, type: "MENTION", actor: { label: "새별" }, postTitle: "모임 공지" };
  assert.equal(notificationText(mention), "새별님이 ‘모임 공지’ 글에서 회원님을 언급했어요.");
  assert.equal(notificationText({ ...mention, onComment: true }), "새별님이 ‘모임 공지’ 댓글에서 회원님을 언급했어요.");
});

test("머리글 버튼 이름은 읽지 않은 수를 글자로 말한다", () => {
  assert.equal(unreadLabel(3), "알림, 읽지 않은 알림 3개");
  assert.equal(unreadLabel(0), "알림, 읽지 않은 알림 없음");
});

test("모임 신청 결과 알림은 모임 이름과 승인·반려를 알린다", () => {
  assert.equal(notificationText({ ...base, type: "CLUB_APPROVED", clubName: "독서" }), "‘독서’ 모임 신청이 승인됐어요.");
  assert.match(notificationText({ ...base, type: "CLUB_REJECTED", clubName: "독서" }), /반려됐어요/);
});

test("개선 요청 상태 알림과 개발자 오류 알림", () => {
  assert.equal(notificationText({ ...base, type: "FEEDBACK_STATUS", feedbackTitle: "검색이 느려요" }),
    "보낸 개선 요청 ‘검색이 느려요’의 처리 상태가 바뀌었어요.");
  assert.equal(notificationText({ ...base, type: "ERROR_ALERT", errorMessage: "TypeError: x" }), "새 오류가 생겼어요: TypeError: x");
});
