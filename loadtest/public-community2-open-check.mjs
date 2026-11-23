// 공개 배포의 커뮤니티 2단계(댓글·답글·추천·멘션·닉네임)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 새 글·댓글을 만들지 않는다(비회원·데모는 쓸 수 없음). 데모 시작 1회, 데모 데이터는 30분 뒤 서버가 지운다.
// 실제 글·댓글이 있어야 하는 확인(비회원 공지 댓글 열람 등)은 로그인 점검 public-community2-check.mjs 에서 한다.
// 실행: node loadtest/public-community2-open-check.mjs
import { Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const anon = new Client();
const missing = await anon.req("GET", "/api/posts/999999999/comments");
record("비회원 댓글 조회 경로 열림(없는 글 404)", missing.status === 404 && missing.body?.code === "POST_NOT_FOUND", `${missing.status} ${missing.body?.code}`);
const anonWrite = await anon.req("POST", "/api/posts/999999999/comments", { body: "x" });
const anonLike = await anon.req("PUT", "/api/comments/999999999/like");
const anonCandidates = await anon.req("GET", "/api/mentions/candidates?q=회원");
const anonProfile = await anon.req("GET", "/api/me/profile");
record("비회원 쓰기·추천·후보·내 정보 401", [anonWrite, anonLike, anonCandidates, anonProfile].every((r) => r.status === 401),
  `댓글 ${anonWrite.status}, 추천 ${anonLike.status}, 후보 ${anonCandidates.status}, 내 정보 ${anonProfile.status}`);

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const demoWrite = await demo.req("POST", "/api/posts/999999999/comments", { body: "데모 댓글" });
const demoLike = await demo.req("PUT", "/api/comments/999999999/like");
const demoNick = await demo.req("PATCH", "/api/me/profile", { nickname: "데모닉네임" });
record("데모 댓글·추천·닉네임 403", demoWrite.status === 403 && demoLike.status === 403 && demoNick.status === 403,
  `댓글 ${demoWrite.status} ${demoWrite.body?.code}, 추천 ${demoLike.status} ${demoLike.body?.code}, 닉네임 ${demoNick.status} ${demoNick.body?.code}`);
const candidates = await demo.req("GET", "/api/mentions/candidates?q=회원");
const fields = [...new Set((candidates.body ?? []).flatMap((c) => Object.keys(c)))].sort();
const onlyIdAndNickname = Array.isArray(candidates.body) && fields.every((f) => f === "id" || f === "nickname");
record("멘션 후보 응답 필드는 id·nickname 뿐", candidates.status === 200 && onlyIdAndNickname && candidates.body.length <= 10,
  `${candidates.status} ${candidates.body?.length}명, 필드 [${fields.join(", ")}]`);
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
