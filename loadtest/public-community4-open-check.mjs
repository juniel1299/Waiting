// 공개 배포의 커뮤니티 4단계(모임)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 모임을 만들지 않는다(데모는 신청·가입 불가). 데모 시작 1회, 데모 데이터는 30분 뒤 서버가 지운다.
// 실행: node loadtest/public-community4-open-check.mjs
import { Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const anon = new Client();
const anonCalls = await Promise.all([anon.req("GET", "/api/clubs"), anon.req("GET", "/api/me/clubs"), anon.req("POST", "/api/clubs", {})]);
record("비회원 모임 목록·내 모임·신청 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const list = await demo.req("GET", "/api/clubs");
const fields = Object.keys(list.body?.items?.[0] ?? {});
record("데모 승인 모임 목록 200 (반려 사유·version 숨김)", list.status === 200 && Array.isArray(list.body?.items)
  && list.body.items.every((c) => c.rejectReason == null && c.version == null && c.canJoin === false) && list.body.canApply === false,
  `${list.status} ${list.body?.totalElements}개, 신청 가능 ${list.body?.canApply}, 필드 ${fields.length}개`);
const apply = await demo.req("POST", "/api/clubs", { name: "데모", intro: "데모", place: "데모", mode: "ONLINE" });
const join = await demo.req("POST", "/api/clubs/999999999/join");
record("데모 신청·가입 403 CLUB_FORBIDDEN", apply.status === 403 && join.status === 403 && apply.body?.code === "CLUB_FORBIDDEN",
  `신청 ${apply.status} ${apply.body?.code}, 가입 ${join.status} ${join.body?.code}`);
const missing = await demo.req("GET", "/api/clubs/999999999");
const operator = await demo.req("GET", "/api/operator/clubs");
record("없는 모임 404·운영자 목록 403", missing.status === 404 && missing.body?.code === "CLUB_NOT_FOUND" && operator.status === 403,
  `모임 ${missing.status} ${missing.body?.code}, 운영자 목록 ${operator.status}`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
