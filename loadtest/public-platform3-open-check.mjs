// 공개 배포의 확장 3단계(개선사항·에러 로그)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 바꾸는 데이터: 데모 시작 1회(30분 뒤 서버가 지움), "[QA 공개 점검]" 화면 오류 보고 1건(에러 로그 한 줄, 30일 뒤 지워짐).
// 실행: node loadtest/public-platform3-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};
// 가림 규칙(긴 영숫자)에 걸리지 않는 고유 한글 표지
const marker = [...Date.now().toString(16).slice(-8)].map((c) => String.fromCharCode(0xac00 + parseInt(c, 16))).join("");

console.log(`시작 ${new Date().toISOString()} 표지 ${marker}`);
const pages = ["/me/feedback", "/dev/feedback", "/dev/errors"];
const html = await Promise.all(pages.map((p) => fetch(BASE + p).then((r) => r.status)));
record("화면 주소 200 (권한 판단은 화면·API 에서)", html.every((s) => s === 200), pages.map((p, i) => `${p} ${html[i]}`).join(", "));

const anon = new Client();
const report = await anon.req("POST", "/api/errors", { message: `[QA 공개 점검] ${marker} password=hunter2`, path: "/board?token=x", release: "qa" });
record("새 백엔드(V18): 비회원 화면 오류 보고 202", report.status === 202, `${report.status}`);
const anonCalls = await Promise.all([
  anon.req("GET", "/api/dev/errors"),
  anon.req("GET", "/api/dev/feedback"),
  anon.req("POST", "/api/feedback", { category: "IDEA", title: "비회원", body: "x" }),
  anon.req("GET", "/api/me/feedback"),
]);
record("비회원 개발 API·개선 요청 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const demoCalls = await Promise.all([demo.req("GET", "/api/dev/errors"), demo.req("GET", "/api/dev/feedback")]);
record("데모 개발 API 403", demoCalls.every((r) => r.status === 403), demoCalls.map((r) => r.status).join(", "));
const demoSubmit = await demo.req("POST", "/api/feedback", { category: "IDEA", title: "데모", body: "x" });
record("데모 개선 요청 403", demoSubmit.status === 403 && demoSubmit.body?.code === "FEEDBACK_FORBIDDEN", `${demoSubmit.status} ${demoSubmit.body?.code}`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
console.log(`Neon SQL 편집기에서 확인 (가림: hunter2·token 없음, 경로 /board):
select source, message, path, release, occurrences from error_group where message like '%${marker}%';`);
process.exitCode = failed ? 1 : 0;
