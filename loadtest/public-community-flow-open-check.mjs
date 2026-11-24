// 공개 배포의 커뮤니티 전체(1~5단계) 화면·API 를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만. 데이터를 만들지 않는다
// (데모 시작 1회, 30분 뒤 서버가 지운다). 실행: node loadtest/public-community-flow-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const pages = ["/board", "/notices", "/me/notifications", "/clubs/promo", "/clubs", "/me/clubs", "/clubs/new", "/clubs/1/board", "/clubs/1/members"];
const html = await Promise.all(pages.map((p) => fetch(BASE + p).then((r) => r.status)));
record("화면 주소 9개 응답 200 (화면은 브라우저에서 로그인 확인)", html.every((s) => s === 200), pages.map((p, i) => `${p} ${html[i]}`).join(", "));

const anon = new Client();
const notice = await anon.req("GET", "/api/boards/notice/posts");
const closed = await Promise.all(["/api/boards/community/posts", "/api/me/notifications/unread-count", "/api/clubs", "/api/clubs/1/boards/board/posts"]
  .map((p) => anon.req("GET", p)));
record("비회원: 공지 목록 200, 회원 게시판·알림·모임·모임 게시판 401", notice.status === 200 && closed.every((r) => r.status === 401),
  `공지 ${notice.status}, ${closed.map((r) => r.status).join(", ")}`);

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const reads = await Promise.all(["/api/boards/community/posts", "/api/me/notifications", "/api/clubs"].map((p) => demo.req("GET", p)));
const writes = await Promise.all([
  demo.req("POST", "/api/boards/community/posts", { title: "x", body: "y" }),
  demo.req("POST", "/api/posts/999999999/comments", { body: "x" }),
  demo.req("POST", "/api/clubs", { name: "x", intro: "y", place: "z", mode: "ONLINE" }),
  demo.req("PATCH", "/api/me/profile", { nickname: "데모닉" }),
]);
record("데모: 게시판·알림·모임 목록 읽기 200, 글·댓글·모임 신청·닉네임 403", reads.every((r) => r.status === 200) && writes.every((r) => r.status === 403),
  `읽기 ${reads.map((r) => r.status).join("·")}, 쓰기 ${writes.map((r) => `${r.status} ${r.body?.code}`).join("·")}`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
