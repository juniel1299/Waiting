// 공개 배포에서 커뮤니티 1~5단계를 하나로 잇는 전체 흐름을 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community-flow-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 2명 가입(IP 15분 5회 한도 안).
//
// 흐름: A·B 가입 → A 회원 게시판 글 → B 의 멘션 댓글 → A 추천 → A 알림 1건·열기(그 댓글 주소)
//      → A 모임 신청 → 운영자 승인 → A 승인 알림 → B 가입 → B 내부 게시판 글 → A 가 B 임명 → B 모임 공지
//      → A 가 B 회수 → B 공지 403 → A 생성자를 B 에게 이전 → A 모임 탈퇴 → A 내부 접근 403
//      → B 모임 삭제(내부 글·공지·A 의 승인 알림 정리) → A 계정 탈퇴(작성자 '탈퇴한 회원')
// 정리(finally): 남은 모임·글 삭제(운영자), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL(남은 행 0) 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_fa6${tag}`);
const b = new Client(`qa_fb6${tag}`);
const clubs = [];
const posts = [];
const comments = [];
const accounts = [];
const deletedClubs = new Set();
const deletedPosts = new Set();
let failed = false;

const shape = (r) => `${r.status}, 응답 키 [${r.body && typeof r.body === "object" ? Object.keys(r.body).join(", ") : typeof r.body}]`;
async function waitForBackend(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/clubs/999999999/boards/board/posts");
    if (r.status === 404 && r.body?.code === "CLUB_NOT_FOUND") return;
    expect(Date.now() < until && r.status === 404, `5단계 백엔드가 준비되지 않음: ${shape(r)}. 배포(Render)가 끝난 뒤 다시 실행`);
    log("배포 대기: 모임 게시판 API 가 아직 없음. 30초 뒤 다시 확인, 최대 10분");
    await sleep(30 * 1000);
  }
}
const notes = async (who) => ((await who.req("GET", "/api/me/notifications")).body?.items ?? []);

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForBackend(op);
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());
  const [aId, bId] = [(await a.req("GET", "/api/auth/me")).body?.id, (await b.req("GET", "/api/auth/me")).body?.id];
  accounts.push(aId, bId);

  // 1·2단계: 게시판 글·멘션 댓글·추천
  const title = `[QA] 전체 흐름 ${tag}`;
  const post = await a.req("POST", "/api/boards/community/posts", { title, body: "전체 흐름 점검 글" });
  if (post.status === 201) posts.push(post.body.id);
  const c = await b.req("POST", `/api/posts/${post.body?.id}/comments`, { body: `@[A#${aId}] 환영해요` });
  if (c.status === 201) comments.push(c.body.id);
  const like = await a.req("PUT", `/api/comments/${c.body?.id}/like`);
  log(`A 글 ${brief(post)} | B 멘션 댓글 ${brief(c)} 멘션 ${c.body?.mentions?.length}명 | A 추천 ${brief(like)} → ${like.body?.likeCount}`);
  expect(post.status === 201 && c.status === 201 && c.body.mentions?.[0]?.id === aId && like.body?.likeCount === 1, "게시판 흐름이 예상과 달라 멈춤");

  // 3단계: 알림
  const mention = (await notes(a)).find((n) => n.type === "MENTION" && n.postTitle === title);
  const opened = await a.req("POST", `/api/me/notifications/${mention?.id}/open`);
  log(`A 멘션 알림 ${mention ? 1 : 0}건 | 열기 ${brief(opened)} ${opened.body?.href}`);
  expect(opened.body?.href === `/board/${post.body.id}#comment-${c.body.id}`, "알림 흐름이 예상과 달라 멈춤");

  // 4단계: 모임 신청·승인·가입
  const clubName = `[QA] 전체 흐름 모임 ${tag}`;
  const applied = await a.req("POST", "/api/clubs", { name: clubName, intro: "전체 흐름 점검", place: "온라인", mode: "ONLINE" });
  if (applied.status === 201) clubs.push(applied.body.id);
  const club = applied.body?.id;
  const approved = await op.req("POST", `/api/operator/clubs/${club}/approve`, { version: applied.body?.version });
  const approvedNote = (await notes(a)).find((n) => n.type === "CLUB_APPROVED" && n.clubName === clubName);
  const joined = await b.req("POST", `/api/clubs/${club}/join`);
  log(`A 모임 신청 ${brief(applied)} | 운영자 승인 ${brief(approved)} | A 승인 알림 ${approvedNote ? 1 : 0}건 | B 가입 ${brief(joined)} 회원 ${joined.body?.memberCount}명`);
  expect(approved.status === 200 && approvedNote && joined.body?.memberCount === 2, "모임 흐름이 예상과 달라 멈춤");

  // 5단계: 내부 게시판·임명·공지·회수·이전·탈퇴
  const inner = await b.req("POST", `/api/clubs/${club}/boards/board/posts`, { title: "[QA] 내부 글", body: "본문" });
  if (inner.status === 201) posts.push(inner.body.id);
  const appoint = await a.req("PUT", `/api/clubs/${club}/managers/${bId}`);
  const notice = await b.req("POST", `/api/clubs/${club}/boards/notice/posts`, { title: "[QA] 모임 공지", body: "본문" });
  if (notice.status === 201) posts.push(notice.body.id);
  const revoke = await a.req("DELETE", `/api/clubs/${club}/managers/${bId}`);
  const refused = await b.req("POST", `/api/clubs/${club}/boards/notice/posts`, { title: "[QA] 회수 뒤", body: "본문" });
  log(`B 내부 글 ${brief(inner)} | A 가 B 임명 ${brief(appoint)} → B 공지 ${brief(notice)} | 회수 ${brief(revoke)} → B 공지 ${brief(refused)}`);
  expect(inner.status === 201 && appoint.body?.role === "MANAGER" && notice.status === 201 && revoke.body?.role === "MEMBER" && refused.status === 403,
    "역할 흐름이 예상과 달라 멈춤");
  const moved = await a.req("POST", `/api/clubs/${club}/transfer`, { accountId: bId });
  const left = await a.req("POST", `/api/clubs/${club}/leave`);
  const outside = await a.req("GET", `/api/clubs/${club}/boards/board/posts`);
  log(`생성자 B 에게 이전 ${brief(moved)} | A 모임 탈퇴 ${brief(left)} | A 내부 게시판 ${brief(outside)}`);
  expect(moved.status === 200 && left.status === 200 && outside.status === 403, "이전·탈퇴 흐름이 예상과 달라 멈춤");

  // 정리 흐름: 모임 삭제 → 내부 콘텐츠·알림 정리, 계정 탈퇴 → 작성자 익명화
  const del = await b.req("DELETE", `/api/clubs/${club}`);
  if (del.status === 204) deletedClubs.add(club);
  const innerGone = await b.req("GET", `/api/posts/${inner.body.id}`);
  const noteGone = (await notes(a)).filter((n) => n.clubName === clubName).length;
  log(`B 모임 삭제 ${brief(del)} → 내부 글 ${brief(innerGone)} | A 의 그 모임 알림 ${noteGone}건`);
  expect(del.status === 204 && innerGone.status === 404 && noteGone === 0, "모임 삭제 정리가 예상과 달라 멈춤");
  const bye = await a.req("POST", "/api/me/withdraw", { password: a.password });
  if (bye.status === 204) a.loggedIn = false;
  const author = (await b.req("GET", `/api/posts/${post.body.id}`)).body?.author?.label;
  log(`A 계정 탈퇴 ${brief(bye)} → A 글 작성자 표시 "${author}"`);
  expect(bye.status === 204 && author === "탈퇴한 회원", "계정 탈퇴 흐름이 예상과 달라 멈춤");
  log("커뮤니티 전체 흐름 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of clubs.filter((x) => !deletedClubs.has(x))) log(`정리: 모임 ${id} 삭제`, brief(await op.req("DELETE", `/api/clubs/${id}`)));
    for (const id of posts) {
      const r = await op.req("DELETE", `/api/posts/${id}`);
      if (r.status !== 404) log(`정리: 글 ${id} 삭제`, brief(r));
      deletedPosts.add(id);
    }
  }
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (accounts.length) {
    const acc = accounts.filter(Boolean).join(", ");
    const p = posts.length ? posts.join(", ") : "0";
    const cl = clubs.length ? clubs.join(", ") : "0";
    const cm = comments.length ? comments.join(", ") : "0";
    log("Neon SQL 편집기에서 남은 행 확인 (기대값 모두 0, 탈퇴한 계정 행은 정책상 익명화되어 남아 셈에 넣지 않음):");
    console.log(`select (select count(*) from board_post where id in (${p}) or club_id in (${cl})) as posts,
       (select count(*) from comment where id in (${cm}) or post_id in (${p})) as comments,
       (select count(*) from comment_like where comment_id in (${cm})) as likes,
       (select count(*) from mention where post_id in (${p}) or comment_id in (${cm})) as mentions,
       (select count(*) from notification where recipient_account_id in (${acc}) or actor_account_id in (${acc}) or club_id in (${cl})) as notifications,
       (select count(*) from club where id in (${cl})) as clubs,
       (select count(*) from club_member where club_id in (${cl}) or account_id in (${acc})) as members;`);
  }
  process.exitCode = failed ? 1 : 0;
}
