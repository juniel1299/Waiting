// 공개 배포의 확장 6단계(모임 채팅)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 바꾸는 데이터: 데모 시작 1회(30분 뒤 서버가 지움). 메시지는 보내지 않는다(권한 거부만 확인).
// 실행: node loadtest/public-platform6-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const html = await fetch(BASE + "/clubs/1/chat").then((r) => r.status);
record("화면 주소 200 (권한 판단은 화면·API 에서)", html === 200, `/clubs/1/chat ${html}`);

const anon = new Client();
const anonCalls = await Promise.all([anon.req("GET", "/api/clubs/1/chat"), anon.req("POST", "/api/clubs/1/chat", { body: "비회원" })]);
record("비회원 채팅 읽기·쓰기 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const clubs = await demo.req("GET", "/api/clubs");
const club = clubs.body?.items?.[0]?.id;
if (club) {
  const calls = await Promise.all([demo.req("GET", `/api/clubs/${club}/chat`), demo.req("POST", `/api/clubs/${club}/chat`, { body: "데모" })]);
  record("새 백엔드(V20): 회원이 아닌 데모는 채팅 403 CLUB_MEMBERS_ONLY", calls.every((r) => r.status === 403 && r.body?.code === "CLUB_MEMBERS_ONLY"),
    `모임 ${club}: ${calls.map((r) => `${r.status} ${r.body?.code}`).join(", ")}`);
} else {
  record("승인된 모임이 없어 채팅 권한 확인을 건너뜀", true, `${clubs.status}`);
}
const missing = await demo.req("GET", "/api/clubs/999999999/chat");
record("없는 모임 채팅 404", missing.status === 404, `${missing.status} ${missing.body?.code}`);

const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
