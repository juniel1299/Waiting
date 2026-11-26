// 공개 배포의 확장 2단계(프로필·닉네임·즐겨찾기)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 데이터를 바꾸지 않는다(데모 시작 1회, 30분 뒤 서버가 지운다). 실행: node loadtest/public-platform2-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const pages = ["/me/profile", "/me/favorites", "/profiles/1"];
const html = await Promise.all(pages.map((p) => fetch(BASE + p).then((r) => r.status)));
record("화면 주소 200 (권한 판단은 화면·API 에서)", html.every((s) => s === 200), pages.map((p, i) => `${p} ${html[i]}`).join(", "));

const anon = new Client();
const anonCalls = await Promise.all([
  anon.req("GET", "/api/profiles/1"),
  anon.req("GET", "/api/me/favorites"),
  anon.req("GET", "/api/me/nickname/check?value=%EB%B3%84"),
  anon.req("PUT", "/api/clubs/1/favorite"),
]);
record("비회원 프로필·즐겨찾기 API 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const favorites = await demo.req("GET", "/api/me/favorites");
record("새 백엔드(V17): 즐겨찾기 목록 200, 회차·모임 묶음", favorites.status === 200 && Array.isArray(favorites.body?.sessions) && Array.isArray(favorites.body?.clubs),
  `${favorites.status} ${JSON.stringify(favorites.body)}`);
const profile = await demo.req("GET", "/api/me/profile");
const fields = Object.keys(profile.body ?? {}).sort().join(",");
record("내 프로필 응답 필드는 bio·id·nickname·tags 뿐", profile.status === 200 && fields === "bio,id,nickname,tags", `${profile.status} [${fields}]`);
const writes = await Promise.all([demo.req("PATCH", "/api/me/profile", { bio: "데모 소개" }), demo.req("PUT", "/api/clubs/1/favorite")]);
record("데모 프로필 수정·즐겨찾기 403", writes.every((r) => r.status === 403), writes.map((r) => `${r.status} ${r.body?.code}`).join(", "));
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
