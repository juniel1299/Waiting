// 공개 배포의 커뮤니티 5단계(모임 역할·내부 게시판·공지)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community5-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 3명 가입(IP 15분 5회 한도 안).
//
// 흐름: 모임 게시판 API 배포 확인(404 면 최대 10분 대기, 가입 한도 쓰기 전) → A·B·C 가입 → A 모임 신청·운영자 승인 → B 가입(C 는 밖)
//      → B 게시판 글(C 멘션은 연결 안 됨) → C 조회 403 → 운영자 조회 200·쓰기 403 → B 가 공지 403 → B 가 A 대신 임명 시도 403
//      → A 가 B 임명 → B 공지 201 → A 가 B 회수 → B 공지 403 → A 생성자 B 에게 이전 → A 모임 탈퇴 200 → B 모임 삭제 → 글 404
// 정리(finally): 남은 모임 삭제(운영자), A·B·C 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL(남은 모임·글·회원·알림 0) 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_ra5${tag}`);
const b = new Client(`qa_rb5${tag}`);
const c = new Client(`qa_rc5${tag}`);
const clubs = [];
const posts = [];
const deleted = new Set();
let failed = false;

const shape = (r) => `${r.status}, 응답 키 [${r.body && typeof r.body === "object" ? Object.keys(r.body).join(", ") : typeof r.body}]`;
async function waitForClubBoardApi(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/clubs/999999999/boards/board/posts");
    if (r.status === 404 && r.body?.code === "CLUB_NOT_FOUND") return;
    expect(Date.now() < until && r.status === 404, `모임 게시판 API 가 준비되지 않음: ${shape(r)}. 배포(Render)가 끝난 뒤 다시 실행`);
    log("배포 대기: 모임 게시판 API 가 아직 없음 (새 백엔드 배포 전). 30초 뒤 다시 확인, 최대 10분");
    await sleep(30 * 1000);
  }
}
const write = async (who, club, board, title, body) => {
  const r = await who.req("POST", `/api/clubs/${club}/boards/${board}/posts`, { title, body });
  if (r.status === 201) posts.push(r.body.id);
  return r;
};

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForClubBoardApi(op);
  log("모임 게시판 API 확인 (새 백엔드)");
  for (const [n, who] of [["A", a], ["B", b], ["C", c]]) log(n, who.loginId, await who.signupAndLogin());
  const [bId, cId] = [(await b.req("GET", "/api/auth/me")).body?.id, (await c.req("GET", "/api/auth/me")).body?.id];

  const applied = await a.req("POST", "/api/clubs", { name: `[QA] 모임 내부 점검 ${tag}`, intro: "점검", place: "온라인", mode: "ONLINE" });
  if (applied.status === 201) clubs.push(applied.body.id);
  const club = applied.body?.id;
  const approved = await op.req("POST", `/api/operator/clubs/${club}/approve`, { version: applied.body?.version });
  const joined = await b.req("POST", `/api/clubs/${club}/join`);
  log(`A 신청 ${brief(applied)} | 운영자 승인 ${brief(approved)} | B 가입 ${brief(joined)} (C 는 밖)`);
  expect(applied.status === 201 && approved.status === 200 && joined.status === 200, "모임 준비가 예상과 달라 멈춤");

  const p = await write(b, club, "board", "[QA] 내부 글", `@[C#${cId}] 밖의 사람 멘션`);
  const cRead = await c.req("GET", `/api/posts/${p.body?.id}`);
  const opRead = await op.req("GET", `/api/posts/${p.body?.id}`);
  const opWrite = await write(op, club, "board", "[QA] 운영자 글", "본문");
  log(`B 게시판 글 ${brief(p)} 멘션 연결 ${p.body?.mentions?.length}명 | C 조회 ${brief(cRead)} | 운영자 조회 ${brief(opRead)} 삭제 가능 ${opRead.body?.canDelete}·쓰기 ${brief(opWrite)}`);
  expect(p.status === 201 && p.body.mentions.length === 0 && cRead.status === 403 && cRead.body?.code === "CLUB_MEMBERS_ONLY"
    && opRead.status === 200 && opRead.body.canDelete === true && opWrite.status === 403, "모임 게시판 접근 규칙이 예상과 달라 멈춤");

  const bNotice = await write(b, club, "notice", "[QA] 회원 공지", "본문");
  const forged = await b.req("PUT", `/api/clubs/${club}/managers/${bId}`, { role: "MANAGER" });
  const appointed = await a.req("PUT", `/api/clubs/${club}/managers/${bId}`);
  const bNotice2 = await write(b, club, "notice", "[QA] 운영자 공지", "본문");
  log(`B 공지 ${brief(bNotice)} | B 스스로 임명 ${brief(forged)} | A 가 B 임명 ${brief(appointed)} → B 공지 ${brief(bNotice2)}`);
  expect(bNotice.status === 403 && forged.status === 403 && appointed.body?.role === "MANAGER" && bNotice2.status === 201, "임명 규칙이 예상과 달라 멈춤");

  const revoked = await a.req("DELETE", `/api/clubs/${club}/managers/${bId}`);
  const bNotice3 = await write(b, club, "notice", "[QA] 회수 뒤 공지", "본문");
  log(`A 가 B 회수 ${brief(revoked)} ${revoked.body?.role} → B 공지 ${brief(bNotice3)}`);
  expect(revoked.body?.role === "MEMBER" && bNotice3.status === 403, "회수 뒤 권한이 남아 멈춤");

  const aLeave = await a.req("POST", `/api/clubs/${club}/leave`);
  const moved = await a.req("POST", `/api/clubs/${club}/transfer`, { accountId: bId });
  const aLeave2 = await a.req("POST", `/api/clubs/${club}/leave`);
  log(`A(생성자) 탈퇴 ${brief(aLeave)} | 생성자 B 에게 이전 ${brief(moved)} | A 탈퇴 ${brief(aLeave2)}`);
  expect(aLeave.status === 409 && moved.status === 200 && aLeave2.status === 200, "생성자 이전·탈퇴가 예상과 달라 멈춤");

  const del = await b.req("DELETE", `/api/clubs/${club}`);
  if (del.status === 204) deleted.add(club);
  const gone = await b.req("GET", `/api/posts/${p.body.id}`);
  log(`B(새 생성자) 모임 삭제 ${brief(del)} → 내부 글 ${brief(gone)}`);
  expect(del.status === 204 && gone.status === 404, "모임 삭제 뒤 글이 남아 멈춤");
  log("커뮤니티 5단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of clubs.filter((x) => !deleted.has(x))) log(`정리: 모임 ${id} 삭제`, brief(await op.req("DELETE", `/api/clubs/${id}`)));
  }
  for (const [name, who] of [["A", a], ["B", b], ["C", c]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (clubs.length) {
    const ids = clubs.join(", ");
    const p = posts.length ? posts.join(", ") : "0";
    log("Neon SQL 편집기에서 남은 행 확인 (기대값 모두 0):");
    console.log(`select (select count(*) from club where id in (${ids})) as clubs,
       (select count(*) from board_post where id in (${p}) or club_id in (${ids})) as posts,
       (select count(*) from club_member where club_id in (${ids})) as members,
       (select count(*) from notification where club_id in (${ids}) or post_id in (${p})) as notifications;`);
  }
  process.exitCode = failed ? 1 : 0;
}
