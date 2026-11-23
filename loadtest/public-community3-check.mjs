// 공개 배포의 커뮤니티 3단계(알림함)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community3-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 2명 가입(IP 15분 5회 한도 안).
//
// 흐름: A·B 가입 → A 글에서 A·B 멘션(B 만 알림, 자기 멘션 제외) → A 가 글 수정(B 그대로) → B 알림 늘지 않음
//      → A 댓글에서 B 멘션 → B 가 열기: /board/{글}#comment-{댓글}, 읽지 않은 수 줄어듦 → A 가 B 의 알림 읽기 404
//      → A 회차(정원 1) → B 확정·A 대기 → B 취소 두 번 → A 승격 알림 1건, 열기 /sessions/{회차} → 모두 읽음 0
//      → A 글 삭제 → B 의 그 글 알림 사라짐
// 정리(finally): 남은 글 삭제(운영자), 회차 삭제(A), A·B 탈퇴(받은 알림 삭제), 운영자 로그아웃. 끝에 Neon 확인 SQL(남은 행 0) 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_na3${tag}`);
const b = new Client(`qa_nb3${tag}`);
const posts = [];
const deletedPosts = new Set();
const sessions = [];
const accountIds = [];
let failed = false;

const unread = async (who) => (await who.req("GET", "/api/me/notifications/unread-count")).body?.unreadCount;
const items = async (who) => (await who.req("GET", "/api/me/notifications")).body?.items ?? [];

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());
  const aId = (await a.req("GET", "/api/auth/me")).body?.id;
  const bId = (await b.req("GET", "/api/auth/me")).body?.id;
  accountIds.push(aId, bId);

  const title = `[QA] 커뮤니티3 점검 ${tag}`;
  const body = `@[A#${aId}] @[B#${bId}] 알림 점검`;
  const post = await a.req("POST", "/api/boards/community/posts", { title, body });
  if (post.status === 201) posts.push(post.body.id);
  const [aAfter, bAfter] = [await unread(a), await unread(b)];
  log(`A 글 ${brief(post)} /board/${post.body?.id} (A·B 멘션) → 읽지 않은 수 A ${aAfter}, B ${bAfter}`);
  expect(post.status === 201 && aAfter === 0 && bAfter === 1, "멘션 알림·자기 멘션 제외가 예상과 달라 멈춤");

  const edit = await a.req("PATCH", `/api/posts/${post.body.id}`, { title, body: `${body} 수정` });
  const bAfterEdit = await unread(b);
  log(`A 글 수정 ${brief(edit)} (B 그대로) → B 읽지 않은 수 ${bAfterEdit}`);
  expect(edit.status === 200 && bAfterEdit === 1, "수정 때 기존 멘션에게 다시 알려 멈춤");

  const c = await a.req("POST", `/api/posts/${post.body.id}/comments`, { body: `@[B#${bId}] 댓글 멘션` });
  const latest = (await items(b))[0];
  const opened = await b.req("POST", `/api/me/notifications/${latest?.id}/open`);
  const bAfterOpen = await unread(b);
  log(`A 댓글 ${brief(c)} → B 최신 알림 ${latest?.type} onComment=${latest?.onComment} | 열기 ${brief(opened)} ${opened.body?.href} → B 읽지 않은 수 ${bAfterOpen}`);
  expect(c.status === 201 && opened.body?.href === `/board/${post.body.id}#comment-${c.body.id}` && bAfterOpen === 1, "댓글 멘션 알림·열기가 예상과 달라 멈춤");
  const intrude = await a.req("POST", `/api/me/notifications/${latest.id}/read`);
  log(`A 가 B 의 알림 읽기 ${brief(intrude)}`);
  expect(intrude.status === 404, "남의 알림이 404 가 아니라 멈춤");

  const startsAt = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();
  const s = await a.req("POST", "/api/sessions", { title: `[QA] 승격 알림 ${tag}`, startsAt, location: "점검", capacity: 1 });
  if (s.status === 201) sessions.push(s.body.id);
  const held = await b.req("POST", `/api/sessions/${s.body?.id}/reservations`);
  const wait = await a.req("POST", `/api/sessions/${s.body?.id}/waitlist`);
  const [x1, x2] = [await b.req("POST", `/api/reservations/${held.body?.id}/cancel`), await b.req("POST", `/api/reservations/${held.body?.id}/cancel`)];
  const promoted = (await items(a)).filter((n) => n.type === "PROMOTED");
  const openSession = await a.req("POST", `/api/me/notifications/${promoted[0]?.id}/open`);
  log(`A 회차 ${brief(s)} | B 확정 ${brief(held)} | A 대기 ${brief(wait)} | B 취소 두 번 ${brief(x1)}·${brief(x2)} → A 승격 알림 ${promoted.length}건 | 열기 ${brief(openSession)} ${openSession.body?.href}`);
  expect(s.status === 201 && held.status === 201 && wait.status === 201 && promoted.length === 1
    && openSession.body?.href === `/sessions/${s.body.id}`, "승격 알림이 예상과 달라 멈춤");

  const readAll = await b.req("POST", "/api/me/notifications/read-all");
  const bAfterAll = await unread(b);
  log(`B 모두 읽음 ${brief(readAll)} → 읽지 않은 수 ${bAfterAll}`);
  expect(readAll.status === 204 && bAfterAll === 0, "모두 읽음이 예상과 달라 멈춤");

  const del = await a.req("DELETE", `/api/posts/${post.body.id}`);
  if (del.status === 204) deletedPosts.add(post.body.id);
  const left = (await items(b)).filter((n) => n.postTitle === title).length;
  log(`A 글 삭제 ${brief(del)} → B 의 그 글 알림 남음 ${left}건`);
  expect(del.status === 204 && left === 0, "글 삭제 뒤 알림이 남아 멈춤");
  log("커뮤니티 3단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of posts.filter((x) => !deletedPosts.has(x))) log(`정리: 글 ${id} 삭제`, brief(await op.req("DELETE", `/api/posts/${id}`)));
  }
  if (a.loggedIn) for (const id of sessions) log(`정리: 회차 ${id} 삭제`, brief(await a.req("DELETE", `/api/sessions/${id}`)));
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (accountIds.length) {
    const ids = accountIds.filter(Boolean).join(", ");
    const p = posts.length ? posts.join(", ") : "0";
    const s = sessions.length ? sessions.join(", ") : "0";
    log("Neon SQL 편집기에서 남은 행 확인 (기대값 모두 0):");
    console.log(`select (select count(*) from notification where recipient_account_id in (${ids}) or actor_account_id in (${ids})) as notifications,
       (select count(*) from board_post where id in (${p})) as posts,
       (select count(*) from study_session where id in (${s})) as sessions;`);
  }
  process.exitCode = failed ? 1 : 0;
}
