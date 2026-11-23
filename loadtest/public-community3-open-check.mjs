// 공개 배포의 커뮤니티 3단계(알림함)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 알림을 만들지 않는다. 데모 시작 1회, 데모 데이터는 30분 뒤 서버가 지운다.
// 실제 알림이 생기는 확인(멘션·승격·열기·정리)은 로그인 점검 public-community3-check.mjs 에서 한다.
// 실행: node loadtest/public-community3-open-check.mjs
import { Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const anon = new Client();
const anonCalls = await Promise.all([
  anon.req("GET", "/api/me/notifications"),
  anon.req("GET", "/api/me/notifications/unread-count"),
  anon.req("POST", "/api/me/notifications/read-all"),
  anon.req("POST", "/api/me/notifications/999999999/open"),
]);
record("비회원 알림 목록·읽지 않은 수·모두 읽음·열기 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const list = await demo.req("GET", "/api/me/notifications");
const count = await demo.req("GET", "/api/me/notifications/unread-count");
record("데모 알림함 200·읽지 않은 수 일치", list.status === 200 && count.status === 200 && list.body?.unreadCount === count.body?.unreadCount
  && Array.isArray(list.body?.items), `목록 ${list.status} ${list.body?.items?.length}건, 읽지 않은 수 ${count.body?.unreadCount}`);
const read = await demo.req("POST", "/api/me/notifications/999999999/read");
const open = await demo.req("POST", "/api/me/notifications/999999999/open");
record("없거나 남의 알림 읽기·열기 404", read.status === 404 && open.status === 404 && read.body?.code === "NOTIFICATION_NOT_FOUND",
  `읽기 ${read.status} ${read.body?.code}, 열기 ${open.status} ${open.body?.code}`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
