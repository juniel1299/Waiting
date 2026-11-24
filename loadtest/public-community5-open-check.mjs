// 공개 배포의 커뮤니티 5단계(모임 역할·내부 게시판)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 글·모임을 만들지 않는다. 데모 시작 1회, 데모 데이터는 30분 뒤 서버가 지운다.
// 실행: node loadtest/public-community5-open-check.mjs
import { Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const anon = new Client();
const anonCalls = await Promise.all([
  anon.req("GET", "/api/clubs/999999999/boards/board/posts"),
  anon.req("GET", "/api/clubs/999999999/members"),
  anon.req("PUT", "/api/clubs/999999999/managers/1"),
  anon.req("POST", "/api/clubs/999999999/transfer", { accountId: 1 }),
]);
record("비회원 모임 게시판·회원 목록·임명·이전 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const board = await demo.req("GET", "/api/clubs/999999999/boards/board/posts");
const wrongBoard = await demo.req("GET", "/api/clubs/999999999/boards/free/posts");
record("없는 모임 게시판 404·없는 게시판 이름 404", board.status === 404 && board.body?.code === "CLUB_NOT_FOUND"
  && wrongBoard.status === 404 && wrongBoard.body?.code === "BOARD_NOT_FOUND",
  `모임 ${board.status} ${board.body?.code}, 게시판 ${wrongBoard.status} ${wrongBoard.body?.code}`);
const appoint = await demo.req("PUT", "/api/clubs/999999999/managers/1");
const transfer = await demo.req("POST", "/api/clubs/999999999/transfer", { accountId: 1 });
record("데모 임명·이전 403 CLUB_FORBIDDEN", appoint.status === 403 && transfer.status === 403 && appoint.body?.code === "CLUB_FORBIDDEN",
  `임명 ${appoint.status} ${appoint.body?.code}, 이전 ${transfer.status} ${transfer.body?.code}`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
