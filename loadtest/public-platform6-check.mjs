// 공개 배포의 확장 6단계(모임 채팅)를 전용 테스트 계정으로 확인하고 정리한다.
// 모임 승인에 운영자 권한이 필요해 사람이 직접 실행한다: node loadtest/public-platform6-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음).
// 테스트 회원은 2명만 가입한다(같은 IP 가입 제한 15분 5회 안). 비밀번호는 무작위·출력 없음.
//
// 흐름: A·B 가입 → A 모임 신청 → 운영자 승인 → B 가입 → B 는 가입 전 403 이었는지 확인
//      → A 보냄 → B 가 처음 조회·cursor 이어 받기로 받음 → B 연속 6건 중 6번째 429(속도 제한)
//      → B 본인 메시지 삭제 → A 의 이어 받기에 삭제 표시(내용 없음) → B 모임 탈퇴 직후 읽기·쓰기 403
// 정리(finally): A 가 모임 삭제(메시지 함께 삭제), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_pa6a${tag}`);
const b = new Client(`qa_pa6b${tag}`);
let failed = false;
let club = null;
let clubDeleted = false;
const ids = [];

async function waitForBackend(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/clubs/999999999/chat");
    if (r.status === 404 && r.body?.code === "CLUB_NOT_FOUND") return;
    expect(Date.now() < until, `채팅 API 가 준비되지 않음: ${brief(r)}. 배포(Render)가 끝난 뒤 다시 실행`);
    log(`배포 대기: 채팅 API ${brief(r)}. 30초 뒤 다시 확인, 최대 10분`);
    await sleep(30 * 1000);
  }
}

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForBackend(op);
  log("A", a.loginId, await a.signupAndLogin());
  ids.push((await a.req("GET", "/api/auth/me")).body?.id);
  log("B", b.loginId, await b.signupAndLogin());
  ids.push((await b.req("GET", "/api/auth/me")).body?.id);

  const created = await a.req("POST", "/api/clubs", { name: `[QA] 채팅 점검 ${tag}`, intro: "공개 점검용 모임(점검 뒤 삭제)", place: "온라인", mode: "ONLINE" });
  club = created.body?.id;
  const approved = await op.req("POST", `/api/operator/clubs/${club}/approve`, { version: created.body?.version });
  const before = await b.req("GET", `/api/clubs/${club}/chat`);
  const joined = await b.req("POST", `/api/clubs/${club}/join`);
  log(`모임 신청 ${brief(created)} → 승인 ${brief(approved)} → B 가입 전 채팅 ${brief(before)} → B 가입 ${brief(joined)}`);
  expect(created.status === 201 && approved.status === 200 && before.status === 403 && joined.status < 300, "모임 준비·가입 전 차단이 예상과 달라 멈춤");

  const first = await a.req("POST", `/api/clubs/${club}/chat`, { body: "[QA] 첫 메시지" });
  const view = await b.req("GET", `/api/clubs/${club}/chat`);
  const cursor = view.body?.cursor;
  const second = await a.req("POST", `/api/clubs/${club}/chat`, { body: "[QA] 두 번째" });
  const next = await b.req("GET", `/api/clubs/${club}/chat?after=${cursor}`);
  log(`A 보냄 ${brief(first)} → B 처음 조회 ${view.body?.items?.length}건 cursor ${cursor} → A 보냄 ${brief(second)} → B 이어 받기 [${next.body?.items?.map((m) => m.body).join(", ")}]`);
  expect(first.status === 201 && view.body?.items?.[0]?.body === "[QA] 첫 메시지" && next.body?.items?.length === 1
    && next.body.items[0].body === "[QA] 두 번째", "주고받기·이어 받기가 예상과 달라 멈춤");

  const burst = [];
  for (let i = 0; i < 6; i++) burst.push(await b.req("POST", `/api/clubs/${club}/chat`, { body: `[QA] 연속 ${i}` }));
  log(`B 연속 6건 ${burst.map(brief).join("·")}`);
  expect(burst.slice(0, 5).every((r) => r.status === 201) && burst[5].status === 429 && burst[5].body?.code === "CHAT_TOO_FAST",
    "속도 제한이 예상과 달라 멈춤");

  const cursorBeforeDelete = (await a.req("GET", `/api/clubs/${club}/chat`)).body?.cursor;
  const removed = await b.req("DELETE", `/api/clubs/${club}/chat/${burst[0].body?.id}`);
  const changes = await a.req("GET", `/api/clubs/${club}/chat?after=${cursorBeforeDelete}`);
  const mark = changes.body?.items?.find((m) => m.id === burst[0].body?.id);
  log(`B 본인 메시지 삭제 ${brief(removed)} → A 이어 받기 삭제 표시 ${mark?.deleted} 내용 ${JSON.stringify(mark?.body)}`);
  expect(removed.status === 204 && mark?.deleted === true && mark.body === null, "삭제 표시가 예상과 달라 멈춤");

  const left = await b.req("POST", `/api/clubs/${club}/leave`);
  const after = await Promise.all([b.req("GET", `/api/clubs/${club}/chat`), b.req("POST", `/api/clubs/${club}/chat`, { body: "[QA] 나간 뒤" })]);
  log(`B 모임 탈퇴 ${brief(left)} → 직후 읽기·쓰기 ${after.map(brief).join("·")}`);
  expect(left.status < 300 && after.every((r) => r.status === 403), "탈퇴 직후 차단이 예상과 달라 멈춤");
  log("확장 6단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (club && a.loggedIn) {
    const del = await a.req("DELETE", `/api/clubs/${club}`);
    clubDeleted = del.status === 204;
    log("정리: 모임 삭제(메시지 함께)", brief(del));
  }
  if (club && !clubDeleted && op.loggedIn) log("정리: 운영자가 모임 삭제", brief(await op.req("DELETE", `/api/clubs/${club}`)));
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  const known = ids.filter(Boolean).join(", ") || "0";
  log("Neon SQL 편집기에서 확인 (기대값: 0 | 0 | 2 — 남은 모임, 남은 메시지, 탈퇴한 점검 계정):");
  console.log(`select (select count(*) from club where id = ${club ?? 0}) as club_left,
       (select count(*) from club_chat_message where club_id = ${club ?? 0}) as messages_left,
       (select count(*) from account where id in (${known}) and deleted_at is not null) as withdrawn;`);
  process.exitCode = failed ? 1 : 0;
}
