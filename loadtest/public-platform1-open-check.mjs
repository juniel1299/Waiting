// 공개 배포의 확장 1단계(계층형 메뉴·서비스 권한 관리)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 데이터를 바꾸지 않는다(데모 시작 1회, 30분 뒤 서버가 지운다). 실행: node loadtest/public-platform1-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const pages = ["/operator/roles", "/clubs/1/roles", "/board"];
const html = await Promise.all(pages.map((p) => fetch(BASE + p).then((r) => r.status)));
record("화면 주소 200 (권한 판단은 화면·API 에서)", html.every((s) => s === 200), pages.map((p, i) => `${p} ${html[i]}`).join(", "));

const anon = new Client();
const anonCalls = await Promise.all([anon.req("GET", "/api/operator/roles"), anon.req("GET", "/api/operator/roles/changes"), anon.req("PUT", "/api/operator/roles/1/DEVELOPER", { reason: "x" })]);
record("비회원 역할 API 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const me = await demo.req("GET", "/api/auth/me");
record("새 백엔드(V16): 내 정보에 roles 필드, 데모는 역할 없음", me.status === 200 && Array.isArray(me.body?.roles) && me.body.roles.length === 0,
  `${me.status} roles=${JSON.stringify(me.body?.roles)}`);
const demoCalls = await Promise.all([demo.req("GET", "/api/operator/roles"), demo.req("PUT", `/api/operator/roles/${me.body?.id}/OPERATOR`, { reason: "셀프" })]);
record("데모 역할 조회·자기 상승 403", demoCalls.every((r) => r.status === 403), demoCalls.map((r) => r.status).join(", "));
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
