// 공개 배포의 커뮤니티 1단계(회원 게시판·서비스 공지 권한)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-community1-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(비밀번호는 화면에 보이지 않고 저장·출력하지 않음). 일반 회원 2명 가입(IP 15분 5회 한도 안).
//
// 흐름: A·B 가입 → A 글(본문에 <script> 포함: 원문 그대로 저장·응답) → B 의 수정·삭제 403 → A 수정 200(수정됨) → 검색
//      → B 글 → 운영자가 B 글 삭제 204 → 운영자 공지 작성·수정, 비회원 공지 열람, A 의 공지 작성·수정 403 → 운영자 공지 삭제
// 정리(finally): 남은 글 삭제(운영자), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 에서 남은 글이 없는지 볼 SQL 을 출력한다(기대값 0).
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_ca${tag}`);
const b = new Client(`qa_cb${tag}`);
const anon = new Client();
const created = [];
const deleted = new Set();
let failed = false;

const write = (who, board, title, body) => who.req("POST", `/api/boards/${board}/posts`, { title, body });
const track = (res) => {
  if (res.status === 201) created.push(res.body.id);
  return res;
};
const remove = async (who, id) => {
  const r = await who.req("DELETE", `/api/posts/${id}`);
  if (r.status === 204 || r.status === 404) deleted.add(id);
  return r;
};

try {
  const ol = await op.login();
  op.password = null; // 로그인 뒤에는 들고 있지 않는다
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());

  const script = "<script>alert('x')</script>\n둘째 줄 <img src=x onerror=alert(1)>";
  const post = track(await write(a, "community", `[QA] 커뮤니티1 점검 ${tag}`, script));
  expect(post.status === 201, `A 글쓰기 실패 ${brief(post)}`);
  log(`A 글 201 /board/${post.body.id} | 본문 원문 그대로: ${post.body.body === script} | 작성자 "${post.body.author.label}" canEdit=${post.body.canEdit}`);
  expect(post.body.body === script && post.body.edited === false, "본문이 바뀌어 저장됐거나 수정 표시가 틀려 멈춤");

  const bEdit = await b.req("PATCH", `/api/posts/${post.body.id}`, { title: "탈취", body: "x" });
  const bDelete = await b.req("DELETE", `/api/posts/${post.body.id}`);
  log(`B 가 A 글 수정 ${brief(bEdit)}, 삭제 ${brief(bDelete)}`);
  expect(bEdit.status === 403 && bDelete.status === 403, "다른 회원의 수정·삭제가 403 이 아니라 멈춤");

  const aEdit = await a.req("PATCH", `/api/posts/${post.body.id}`, { title: `[QA] 커뮤니티1 점검 ${tag} 수정`, body: script });
  log(`A 수정 ${brief(aEdit)} 수정됨=${aEdit.body?.edited}`);
  expect(aEdit.status === 200 && aEdit.body.edited === true, "작성자 수정이 실패했거나 수정됨 표시가 없어 멈춤");

  const found = await b.req("GET", `/api/boards/community/posts?q=${encodeURIComponent(`점검 ${tag}`)}&field=TITLE`);
  log(`제목 검색 ${brief(found)} ${found.body?.totalElements}건 [${found.body?.items?.map((p) => p.title).join(", ")}]`);
  expect(found.status === 200 && found.body.totalElements === 1, "검색 결과가 1건이 아니라 멈춤");

  const bPost = track(await write(b, "community", `[QA] 커뮤니티1 B 글 ${tag}`, "운영자가 지울 글"));
  const opDelete = await remove(op, bPost.body.id);
  const gone = await a.req("GET", `/api/posts/${bPost.body.id}`);
  log(`B 글 ${brief(bPost)} → 운영자 삭제 ${brief(opDelete)} → 상세 ${brief(gone)}`);
  expect(opDelete.status === 204 && gone.status === 404, "운영자의 회원 글 삭제가 예상과 달라 멈춤");

  const notice = track(await write(op, "notice", `[QA] 공지 점검 ${tag}`, "공지 본문"));
  const noticeEdit = await op.req("PATCH", `/api/posts/${notice.body?.id}`, { title: `[QA] 공지 점검 ${tag} 수정`, body: "공지 본문 2" });
  const anonNotice = await anon.req("GET", `/api/posts/${notice.body?.id}`);
  const aNotice = await write(a, "notice", "회원 공지", "x");
  const aNoticeEdit = await a.req("PATCH", `/api/posts/${notice.body?.id}`, { title: "x", body: "y" });
  log(`운영자 공지 ${brief(notice)}·수정 ${brief(noticeEdit)} | 비회원 공지 열람 ${brief(anonNotice)} 작성자 "${anonNotice.body?.author?.label}" | A 공지 작성 ${brief(aNotice)}·수정 ${brief(aNoticeEdit)}`);
  expect(notice.status === 201 && noticeEdit.status === 200 && anonNotice.status === 200 && aNotice.status === 403 && aNoticeEdit.status === 403,
    "공지 권한이 예상과 달라 멈춤");
  const noticeDelete = await remove(op, notice.body.id);
  log(`운영자 공지 삭제 ${brief(noticeDelete)}`);
  expect(noticeDelete.status === 204, "공지 삭제 실패");
  log("커뮤니티 1단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    for (const id of created.filter((x) => !deleted.has(x))) log(`정리: 글 ${id} 삭제`, brief(await remove(op, id)));
  }
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (created.length) {
    log(`만든 글 id: ${created.join(", ")}. Neon SQL 편집기에서 남은 글 확인(기대값 0):`);
    console.log(`select count(*) from board_post where id in (${created.join(", ")});`);
  }
  process.exitCode = failed ? 1 : 0;
}
