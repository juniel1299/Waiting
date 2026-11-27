// 공개 배포의 채팅 이전 기록 조회(PR #58)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 바꾸는 데이터: 데모 시작 1회(30분 뒤 서버가 지움). 메시지는 보내지 않는다.
// 실행: node loadtest/public-chat-history-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()} ${BASE}`);
const anon = new Client();
const anonHistory = await anon.req("GET", "/api/clubs/1/chat?before=1000&limit=50");
record("비회원 이전 기록 401", anonHistory.status === 401, `${anonHistory.status}`);

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const clubs = await demo.req("GET", "/api/clubs");
const club = clubs.body?.items?.[0]?.id ?? 1;

const both = await demo.req("GET", `/api/clubs/${club}/chat?before=10&after=5`);
record("새 백엔드: before 와 after 를 함께 주면 400 VALIDATION_FAILED", both.status === 400 && both.body?.code === "VALIDATION_FAILED",
  `모임 ${club}: ${both.status} ${both.body?.code}`);
const history = await demo.req("GET", `/api/clubs/${club}/chat?before=1000&limit=50`);
record("회원이 아닌 데모는 이전 기록도 403 CLUB_MEMBERS_ONLY", history.status === 403 && history.body?.code === "CLUB_MEMBERS_ONLY",
  `모임 ${club}: ${history.status} ${history.body?.code}`);
const missing = await demo.req("GET", "/api/clubs/999999999/chat?before=10");
record("없는 모임 이전 기록 404", missing.status === 404, `${missing.status} ${missing.body?.code}`);

const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
