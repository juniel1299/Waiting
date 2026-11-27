// 공개 배포의 확장 7단계(모임 사진첩)를 비밀번호 없이 확인한다: 비회원과 데모 체험 계정만.
// 바꾸는 데이터: 데모 시작 1회(30분 뒤 서버가 지움). 사진은 올리지 않는다(권한 거부만 확인).
// 실행: node loadtest/public-platform7-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const html = await fetch(BASE + "/clubs/1/photos").then((r) => r.status);
record("화면 주소 200 (권한 판단은 화면·API 에서)", html === 200, `/clubs/1/photos ${html}`);

const anon = new Client();
const anonCalls = await Promise.all([
  anon.req("GET", "/api/clubs/1/photos"),
  anon.req("GET", "/api/clubs/1/photos/1/thumb"),
  anon.req("DELETE", "/api/clubs/1/photos/1"),
]);
record("비회원 사진첩 목록·썸네일·삭제 401", anonCalls.every((r) => r.status === 401), anonCalls.map((r) => r.status).join(", "));

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
record("데모 시작", start.status === 201, `${start.status}`);
const club = (await demo.req("GET", "/api/clubs")).body?.items?.[0]?.id;
if (club) {
  const list = await demo.req("GET", `/api/clubs/${club}/photos`);
  const form = new FormData();
  form.append("file", new Blob([Buffer.from("not an image")], { type: "image/jpeg" }), "x.jpg");
  const upload = await demo.req("POST", `/api/clubs/${club}/photos`, form);
  record("새 백엔드(V21): 회원이 아닌 데모는 사진첩 403 CLUB_MEMBERS_ONLY", [list, upload].every((r) => r.status === 403 && r.body?.code === "CLUB_MEMBERS_ONLY"),
    `모임 ${club}: 목록 ${list.status} ${list.body?.code}, 올리기 ${upload.status} ${upload.body?.code}`);
} else {
  record("승인된 모임이 없어 사진첩 권한 확인을 건너뜀", true, "-");
}
const missing = await demo.req("GET", "/api/clubs/999999999/photos");
record("없는 모임 사진첩 404", missing.status === 404, `${missing.status} ${missing.body?.code}`);

const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
