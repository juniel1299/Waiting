// 공개 배포의 확장 3단계(개선사항·에러 로그)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-platform3-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 테스트 회원 2명 가입(비밀번호 무작위·출력 없음).
//
// 흐름: 새 백엔드 확인 → A·B 가입 → A 개선 요청 접수 → 운영자 혼자는 개발 API 403, B(회원)는 A 요청 404
//      → 운영자가 B 에게 개발자 부여(사유) → B 가 A 의 요청을 찾음 → 사유 없이 보류 400 → 사유와 함께 보류 200
//      → A 화면에 상태·사유, 알림 → 화면 오류 보고 → B 의 에러 로그에 가려진 채(비밀번호·이메일 없음, 경로 틀) 보임
// 정리(finally): B 개발자 회수, A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL 과 (선택) 점검 데이터 정리 SQL 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const marker = [...Date.now().toString(16).slice(-8)].map((c) => String.fromCharCode(0xac00 + parseInt(c, 16))).join("");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_pa3a${tag}`);
const b = new Client(`qa_pa3b${tag}`);
let failed = false;
let aId = null;
let bId = null;
let granted = false;
let feedbackId = null;

async function waitForBackend() {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await new Client().req("POST", "/api/errors", { message: `[QA] 배포 확인 ${marker}`, path: "/" });
    if (r.status === 202) return;
    expect(Date.now() < until, `에러 보고 API 가 준비되지 않음: ${r.status}. 배포(Render)가 끝난 뒤 다시 실행`);
    log(`배포 대기: 에러 보고 API ${r.status}. 30초 뒤 다시 확인, 최대 10분`);
    await sleep(30 * 1000);
  }
}

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForBackend();
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());
  aId = (await a.req("GET", "/api/auth/me")).body?.id;
  bId = (await b.req("GET", "/api/auth/me")).body?.id;

  const sent = await a.req("POST", "/api/feedback", { category: "IDEA", title: `[QA] 공개 점검 ${marker}`, body: "공개 점검용 요청", pagePath: "/board?x=1" });
  feedbackId = sent.body?.id;
  log(`A 개선 요청 ${brief(sent)} 상태 ${sent.body?.status} 경로 ${sent.body?.pagePath}`);
  expect(sent.status === 201 && sent.body?.status === "RECEIVED" && sent.body?.pagePath === "/board", "접수가 예상과 달라 멈춤");

  const opOnly = await Promise.all([op.req("GET", "/api/dev/feedback"), op.req("GET", "/api/dev/errors")]);
  const bBefore = await b.req("GET", `/api/feedback/${feedbackId}`);
  log(`운영자 혼자 개발 API ${opOnly.map(brief).join("·")} | B(회원)가 A 요청 조회 ${brief(bBefore)}`);
  expect(opOnly.every((r) => r.status === 403) && bBefore.status === 404, "개발 API·남의 요청 차단이 예상과 달라 멈춤");

  const grant = await op.req("PUT", `/api/operator/roles/${bId}/DEVELOPER`, { reason: "[QA] 3단계 공개 점검" });
  granted = grant.status === 200;
  const found = await b.req("GET", `/api/dev/feedback?q=${encodeURIComponent(marker)}`);
  const noReason = await b.req("PATCH", `/api/dev/feedback/${feedbackId}`, { status: "ON_HOLD", reason: " ", version: 0 });
  const hold = await b.req("PATCH", `/api/dev/feedback/${feedbackId}`, { status: "ON_HOLD", reason: "[QA] 다음 분기 검토", version: 0 });
  log(`B 개발자 부여 ${brief(grant)} → 검색 ${found.body?.items?.length}건 → 사유 없이 보류 ${brief(noReason)} → 사유와 보류 ${brief(hold)}`);
  expect(granted && found.body?.items?.some((f) => f.id === feedbackId) && noReason.status === 400 && hold.status === 200, "개발자 상태 변경이 예상과 달라 멈춤");

  const seen = await a.req("GET", `/api/feedback/${feedbackId}`);
  const note = ((await a.req("GET", "/api/me/notifications")).body?.items ?? []).find((n) => n.type === "FEEDBACK_STATUS");
  log(`A 가 본 상태 ${seen.body?.status} 사유 "${seen.body?.history?.[0]?.reason}" | 알림 ${note ? "있음" : "없음"}`);
  expect(seen.body?.status === "ON_HOLD" && seen.body?.history?.[0]?.reason === "[QA] 다음 분기 검토" && note, "작성자 화면·알림이 예상과 달라 멈춤");

  const reported = await a.req("POST", "/api/errors", { message: `[QA] 화면 오류 ${marker} password=hunter2 a@b.co`, path: "/sessions/12?token=x" });
  const errors = await b.req("GET", `/api/dev/errors?q=${encodeURIComponent(marker)}&source=FRONTEND`);
  const row = errors.body?.items?.find((g) => g.message.includes("화면 오류"));
  log(`화면 오류 보고 ${brief(reported)} → B 에러 로그 "${row?.message}" 경로 ${row?.path} 횟수 ${row?.occurrences}`);
  expect(reported.status === 202 && row && !row.message.includes("hunter2") && !row.message.includes("a@b.co") && row.path === "/sessions/:id",
    "에러 로그 가림·경로 틀이 예상과 달라 멈춤");
  log("확장 3단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (granted && op.loggedIn) log("정리: B 개발자 회수", brief(await op.req("DELETE", `/api/operator/roles/${bId}/DEVELOPER`, { reason: "[QA] 3단계 공개 점검 정리" })));
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  const ids = [aId, bId].filter(Boolean).join(", ") || "0";
  log("Neon SQL 편집기에서 확인 (기대값: 0 | 2 | 가린 메시지에 hunter2·이메일 없음):");
  console.log(`select (select count(*) from account_role where account_id in (${ids})) as dev_role_left,
       (select count(*) from account where id in (${ids}) and deleted_at is not null) as withdrawn,
       (select string_agg(message, ' | ') from error_group where message like '%${marker}%') as masked_messages;`);
  log("(선택) 점검용 개선 요청·오류 묶음 정리 SQL (개선 요청은 작성자가 탈퇴해도 남는 것이 정책이라, 점검 데이터만 직접 지운다):");
  console.log(`delete from feedback where id = ${feedbackId ?? 0} and title like '[QA]%';
delete from error_group where message like '%${marker}%';`);
  process.exitCode = failed ? 1 : 0;
}
