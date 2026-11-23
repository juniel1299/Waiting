// 공개 배포의 커뮤니티 2단계(댓글·한 단계 답글·추천·멘션·닉네임)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community2-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 2명 가입(IP 15분 5회 한도 안).
//
// 흐름: A·B 가입 → A 글 → B 댓글·A 답글·답글의 답글 400 → B 추천 두 번 동시 PUT 뒤 개수 1, 취소 뒤 0
//      → B 닉네임 설정 → A 가 닉네임으로 B 를 멘션 후보에서 찾기(응답 필드 id·nickname 뿐) → 멘션 저장·표시
//      → 남의 댓글 수정·삭제 403 → 운영자가 B 댓글 삭제 → 부모 댓글 삭제 시 답글 함께 삭제
//      → 운영자 공지에 B 댓글, 비회원이 공지 댓글 열람 → 글 삭제 시 댓글 함께 삭제
// 정리(finally): 남은 글 삭제(운영자), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL(남은 글·댓글·추천·멘션 0) 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_ma2${tag}`);
const b = new Client(`qa_mb2${tag}`);
const anon = new Client();
const posts = [];
const comments = [];
const deletedPosts = new Set();
let failed = false;

const comment = async (who, postId, body, parentId) => {
  const r = await who.req("POST", `/api/posts/${postId}/comments`, parentId ? { body, parentId } : { body });
  if (r.status === 201) comments.push(r.body.id);
  return r;
};
const newPost = async (who, board, title) => {
  const r = await who.req("POST", `/api/boards/${board}/posts`, { title, body: "댓글 점검 본문" });
  if (r.status === 201) posts.push(r.body.id);
  return r;
};

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());

  const post = await newPost(a, "community", `[QA] 커뮤니티2 점검 ${tag}`);
  expect(post.status === 201, `A 글 실패 ${brief(post)}`);
  const c1 = await comment(b, post.body.id, "B 의 댓글");
  const r1 = await comment(a, post.body.id, "A 의 답글", c1.body?.id);
  const rr = await comment(b, post.body.id, "답글의 답글", r1.body?.id);
  log(`A 글 /board/${post.body.id} | B 댓글 ${brief(c1)} | A 답글 ${brief(r1)} | 답글의 답글 ${brief(rr)}`);
  expect(c1.status === 201 && r1.status === 201 && rr.status === 400 && rr.body?.code === "REPLY_DEPTH_EXCEEDED", "댓글·답글 규칙이 예상과 달라 멈춤");

  const [l1, l2] = await Promise.all([b.req("PUT", `/api/comments/${c1.body.id}/like`), b.req("PUT", `/api/comments/${c1.body.id}/like`)]);
  const unlike = await b.req("DELETE", `/api/comments/${c1.body.id}/like`);
  log(`B 추천 동시 2번 ${brief(l1)}·${brief(l2)} → 개수 ${Math.max(l1.body?.likeCount ?? -1, l2.body?.likeCount ?? -1)} | 취소 ${brief(unlike)} → ${unlike.body?.likeCount}`);
  expect(l1.status === 200 && l2.status === 200 && Math.max(l1.body.likeCount, l2.body.likeCount) === 1 && unlike.body?.likeCount === 0, "추천 중복 방지가 예상과 달라 멈춤");

  const nick = `점검${tag.slice(-6)}`;
  const setNick = await b.req("PATCH", "/api/me/profile", { nickname: nick });
  const found = await a.req("GET", `/api/mentions/candidates?q=${encodeURIComponent(nick)}`);
  const fields = [...new Set((found.body ?? []).flatMap((c) => Object.keys(c)))].sort();
  const bId = found.body?.find((c) => c.nickname === nick)?.id;
  log(`B 닉네임 ${brief(setNick)} "${setNick.body?.nickname}" | 후보 검색 ${brief(found)} ${found.body?.length}명, 필드 [${fields.join(", ")}], 로그인 아이디 포함: ${JSON.stringify(found.body).includes(b.loginId)}`);
  expect(setNick.status === 200 && bId && fields.join() === "id,nickname" && !JSON.stringify(found.body).includes(b.loginId), "닉네임·후보 응답이 예상과 달라 멈춤");
  const m = await comment(a, post.body.id, `@[${nick}#${bId}] 확인 부탁해요`);
  const list = await anon.req("GET", `/api/posts/${post.body.id}/comments`);
  const shown = (await a.req("GET", `/api/posts/${post.body.id}/comments`)).body?.find((c) => c.id === m.body?.id)?.mentions;
  log(`멘션 댓글 ${brief(m)} → 표시 ${JSON.stringify(shown)} | 비회원 회원 게시판 댓글 ${brief(list)}`);
  expect(m.status === 201 && shown?.[0]?.id === bId && shown[0].nickname === nick && list.status === 401, "멘션 저장·표시가 예상과 달라 멈춤");

  const editOther = await a.req("PATCH", `/api/comments/${c1.body.id}`, { body: "남의 댓글" });
  const deleteOther = await a.req("DELETE", `/api/comments/${c1.body.id}`);
  log(`A 가 B 댓글 수정 ${brief(editOther)}·삭제 ${brief(deleteOther)}`);
  expect(editOther.status === 403 && deleteOther.status === 403, "남의 댓글 수정·삭제가 403 이 아니라 멈춤");

  const c2 = await comment(b, post.body.id, "운영자가 지울 댓글");
  const opDel = await op.req("DELETE", `/api/comments/${c2.body.id}`);
  const parentDel = await b.req("DELETE", `/api/comments/${c1.body.id}`);
  const after = (await a.req("GET", `/api/posts/${post.body.id}/comments`)).body ?? [];
  const ids = after.flatMap((c) => [c.id, ...c.replies.map((r) => r.id)]);
  log(`운영자 댓글 삭제 ${brief(opDel)} | B 부모 댓글 삭제 ${brief(parentDel)} → 답글 남음: ${ids.includes(r1.body.id)}, 남은 댓글 ${ids.length}개`);
  expect(opDel.status === 204 && parentDel.status === 204 && !ids.includes(r1.body.id) && !ids.includes(c1.body.id), "댓글 삭제가 예상과 달라 멈춤");

  const notice = await newPost(op, "notice", `[QA] 공지 댓글 점검 ${tag}`);
  const nc = await comment(b, notice.body?.id, "공지에 단 댓글");
  const anonNotice = await anon.req("GET", `/api/posts/${notice.body?.id}/comments`);
  log(`공지 ${brief(notice)} | B 공지 댓글 ${brief(nc)} | 비회원 공지 댓글 열람 ${brief(anonNotice)} ${anonNotice.body?.length}개, 쓰기 가능: ${anonNotice.body?.[0]?.canEdit}`);
  expect(nc.status === 201 && anonNotice.status === 200 && anonNotice.body?.[0]?.canEdit === false, "공지 댓글 열람이 예상과 달라 멈춤");

  const postDel = await a.req("DELETE", `/api/posts/${post.body.id}`);
  if (postDel.status === 204) deletedPosts.add(post.body.id);
  const gone = await b.req("GET", `/api/posts/${post.body.id}/comments`);
  log(`A 글 삭제 ${brief(postDel)} → 댓글 목록 ${brief(gone)}`);
  expect(postDel.status === 204 && gone.status === 404, "글 삭제 시 댓글 정리가 예상과 달라 멈춤");
  log("커뮤니티 2단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of posts.filter((x) => !deletedPosts.has(x))) log(`정리: 글 ${id} 삭제`, brief(await op.req("DELETE", `/api/posts/${id}`)));
  }
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (posts.length) {
    const p = posts.join(", ");
    const c = comments.length ? comments.join(", ") : "0";
    log("Neon SQL 편집기에서 남은 행 확인 (기대값 모두 0):");
    console.log(`select (select count(*) from board_post where id in (${p})) as posts,
       (select count(*) from comment where id in (${c}) or post_id in (${p})) as comments,
       (select count(*) from comment_like where comment_id in (${c})) as likes,
       (select count(*) from mention where comment_id in (${c}) or post_id in (${p})) as mentions;`);
  }
  process.exitCode = failed ? 1 : 0;
}
