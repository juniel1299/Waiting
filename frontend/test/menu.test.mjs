// 계층형 메뉴 노출·현재 위치 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { activeGroup, activeHref, visibleMenu } from "../src/lib/menu.ts";

const labels = (menu) => menu.flatMap((g) => g.items.map((i) => i.label));

test("회원에게는 관리 메뉴가 없고, 운영자에게만 관리 묶음이 있다", () => {
  const member = visibleMenu({ role: "PARTICIPANT", demo: false, roles: [] });
  assert.ok(!member.some((g) => g.id === "admin"));
  assert.ok(labels(member).includes("내 신청"));
  const op = visibleMenu({ role: "OPERATOR", demo: false, roles: ["OPERATOR"] });
  assert.ok(labels(op).includes("서비스 권한 관리"));
  assert.ok(!labels(op).includes("내 신청"));
});

test("개발자 역할만으로는 관리 메뉴가 생기지 않는다", () => {
  const dev = visibleMenu({ role: "PARTICIPANT", demo: false, roles: ["DEVELOPER"] });
  assert.ok(!dev.some((g) => g.id === "admin"));
});

test("데모는 쓰기·내 정보 메뉴가 없다", () => {
  const demo = labels(visibleMenu({ role: "PARTICIPANT", demo: true, roles: [] }));
  assert.ok(!demo.includes("회차 만들기"));
  assert.ok(!demo.includes("내 모임"));
  assert.ok(!demo.includes("프로필"));
  assert.ok(!demo.includes("즐겨찾기"));
});

test("회원은 내 활동 > 즐겨찾기와 내 정보 > 프로필이 있고, 프로필 화면 안에서도 그 항목이 강조된다", () => {
  const member = visibleMenu({ role: "PARTICIPANT", demo: false, roles: [] });
  assert.ok(member.find((g) => g.id === "activity").items.some((i) => i.label === "즐겨찾기"));
  assert.ok(member.find((g) => g.id === "account").items.some((i) => i.label === "프로필"));
  assert.equal(activeHref(member, "/me/favorites"), "/me/favorites");
});

test("현재 위치는 가장 길게 맞는 주소의 항목과 그 묶음", () => {
  const op = visibleMenu({ role: "OPERATOR", demo: false, roles: ["OPERATOR"] });
  assert.equal(activeHref(op, "/clubs/12/board"), "/clubs");
  assert.equal(activeHref(op, "/clubs/promo"), "/clubs/promo");
  assert.equal(activeHref(op, "/operator/roles"), "/operator/roles");
  assert.equal(activeGroup(op, "/board/3"), "board");
  assert.equal(activeHref(op, "/"), null);
});
