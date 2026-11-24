// 공개 배포의 커뮤니티 4단계(모임 신청·승인·가입)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community4-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 2명 가입(IP 15분 5회 한도 안).
//
// 흐름: 모임 API 배포 확인(404 면 최대 10분 대기, 가입 한도 쓰기 전) → A·B 가입 → A 신청(승인 대기) → B 에게는 404
//      → 운영자 반려(사유) → A 가 사유 확인·고쳐 재신청 → 운영자가 낡은 version 으로 승인 409 → 새 version 승인
//      → A 승인 알림 열기 /clubs/{id} → B 목록 검색·가입 두 번 동시 → 회원 2명 → B 탈퇴 → A(만든 사람) 탈퇴 409
//      → A 모임 삭제 → B 에게 404
// 정리(finally): 남은 모임 삭제(운영자), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL(남은 모임·회원·알림 0) 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_ka4${tag}`);
const b = new Client(`qa_kb4${tag}`);
const clubs = [];
const deleted = new Set();
let failed = false;

const shape = (r) => `${r.status}, 응답 키 [${r.body && typeof r.body === "object" ? Object.keys(r.body).join(", ") : typeof r.body}]`;
const DEPLOY_WAIT_MS = 10 * 60 * 1000;
async function waitForClubApi(who) {
  const until = Date.now() + DEPLOY_WAIT_MS;
  for (;;) {
    const r = await who.req("GET", "/api/operator/clubs");
    if (r.status === 200) return;
    expect(r.status === 404 && Date.now() < until, `모임 API 가 준비되지 않음: ${shape(r)}. 배포(Render)가 끝난 뒤 다시 실행`);
    log("배포 대기: 모임 API 가 아직 404 (새 백엔드 배포 전). 30초 뒤 다시 확인, 최대 10분");
    await sleep(30 * 1000);
  }
}
const decide = (action, id, version, reason) => op.req("POST", `/api/operator/clubs/${id}/${action}`, { version, reason });

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForClubApi(op);
  log("모임 API 확인 200 (새 백엔드)");
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());

  const name = `[QA] 모임 점검 ${tag}`;
  const applied = await a.req("POST", "/api/clubs", { name, intro: "점검용 모임 소개", place: "온라인", mode: "ONLINE" });
  if (applied.status === 201) clubs.push(applied.body.id);
  const id = applied.body?.id;
  const hidden = await b.req("GET", `/api/clubs/${id}`);
  log(`A 신청 ${brief(applied)} 상태 ${applied.body?.status} | B 가 대기 모임 조회 ${brief(hidden)}`);
  expect(applied.status === 201 && applied.body.status === "PENDING" && hidden.status === 404, "신청·비공개가 예상과 달라 멈춤");

  const rejected = await decide("reject", id, applied.body.version, "점검용 반려 사유");
  const seen = await a.req("GET", `/api/clubs/${id}`);
  const edited = await a.req("PATCH", `/api/clubs/${id}`, { name, intro: "고친 소개", place: "온라인 화상", mode: "ONLINE" });
  log(`운영자 반려 ${brief(rejected)} → A 가 본 사유 "${seen.body?.rejectReason}" | 고쳐 재신청 ${brief(edited)} 상태 ${edited.body?.status}`);
  expect(rejected.status === 200 && seen.body?.rejectReason === "점검용 반려 사유" && edited.body?.status === "PENDING", "반려·재신청이 예상과 달라 멈춤");

  const stale = await decide("approve", id, seen.body.version);
  const approved = await decide("approve", id, edited.body.version);
  const note = ((await a.req("GET", "/api/me/notifications")).body?.items ?? []).find((n) => n.type === "CLUB_APPROVED" && n.clubName === name);
  const opened = await a.req("POST", `/api/me/notifications/${note?.id}/open`);
  log(`낡은 version 승인 ${brief(stale)} | 새 version 승인 ${brief(approved)} | A 승인 알림 열기 ${brief(opened)} ${opened.body?.href}`);
  expect(stale.status === 409 && stale.body?.code === "CLUB_CHANGED" && approved.status === 200 && opened.body?.href === `/clubs/${id}`, "승인 경합·알림이 예상과 달라 멈춤");

  const found = await b.req("GET", `/api/clubs?q=${encodeURIComponent(name)}`);
  const [j1, j2] = await Promise.all([b.req("POST", `/api/clubs/${id}/join`), b.req("POST", `/api/clubs/${id}/join`)]);
  log(`B 검색 ${brief(found)} ${found.body?.totalElements}개 | 가입 동시 2번 ${brief(j1)}·${brief(j2)} → 회원 ${Math.max(j1.body?.memberCount ?? -1, j2.body?.memberCount ?? -1)}명`);
  expect(found.body?.totalElements === 1 && j1.status === 200 && j2.status === 200 && Math.max(j1.body.memberCount, j2.body.memberCount) === 2, "검색·중복 가입 방지가 예상과 달라 멈춤");

  const left = await b.req("POST", `/api/clubs/${id}/leave`);
  const creatorLeave = await a.req("POST", `/api/clubs/${id}/leave`);
  log(`B 탈퇴 ${brief(left)} → 회원 ${left.body?.memberCount}명 | A(만든 사람) 탈퇴 ${brief(creatorLeave)}`);
  expect(left.body?.memberCount === 1 && creatorLeave.status === 409, "모임 탈퇴 규칙이 예상과 달라 멈춤");

  const del = await a.req("DELETE", `/api/clubs/${id}`);
  if (del.status === 204) deleted.add(id);
  const gone = await b.req("GET", `/api/clubs/${id}`);
  log(`A 모임 삭제 ${brief(del)} → B 조회 ${brief(gone)}`);
  expect(del.status === 204 && gone.status === 404, "모임 삭제가 예상과 달라 멈춤");
  log("커뮤니티 4단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of clubs.filter((x) => !deleted.has(x))) log(`정리: 모임 ${id} 삭제`, brief(await op.req("DELETE", `/api/clubs/${id}`)));
  }
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (clubs.length) {
    const ids = clubs.join(", ");
    log("Neon SQL 편집기에서 남은 행 확인 (기대값 모두 0):");
    console.log(`select (select count(*) from club where id in (${ids})) as clubs,
       (select count(*) from club_member where club_id in (${ids})) as members,
       (select count(*) from notification where club_id in (${ids})) as notifications;`);
  }
  process.exitCode = failed ? 1 : 0;
}
