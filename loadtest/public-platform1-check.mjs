// 공개 배포의 확장 1단계(서비스 역할)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-platform1-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음). 일반 회원 1명 가입.
//
// 흐름: 새 백엔드 확인 → A 가입 → A 의 역할 API 403(자기 상승 불가) → 운영자가 A 에게 개발자 부여(사유)
//      → A 의 같은 세션 /api/auth/me 에 DEVELOPER 반영 → A 의 운영자 API(계정·사용량·역할) 403
//      → 운영자 자기 역할 변경 403 → 운영자가 개발자 회수 → A 세션에서 역할 사라짐 → 변경 기록 2건(부여·회수, 사유·처리자)
// 정리(finally): A 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL 출력.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_pa1${tag}`);
let failed = false;
let aId = null;

async function waitForRoleApi(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/operator/roles");
    if (r.status === 200) return;
    expect(r.status === 404 && Date.now() < until, `역할 API 가 준비되지 않음: ${r.status}. 배포(Render)가 끝난 뒤 다시 실행`);
    log("배포 대기: 역할 API 가 아직 404. 30초 뒤 다시 확인, 최대 10분");
    await sleep(30 * 1000);
  }
}

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForRoleApi(op);
  const opId = (await op.req("GET", "/api/auth/me")).body?.id;
  log("A", a.loginId, await a.signupAndLogin());
  aId = (await a.req("GET", "/api/auth/me")).body?.id;

  const self = await a.req("PUT", `/api/operator/roles/${aId}/OPERATOR`, { reason: "셀프 상승" });
  log(`A 자기 운영자 부여 ${brief(self)}`);
  expect(self.status === 403, "회원의 자기 상승이 막히지 않아 멈춤");

  const grant = await op.req("PUT", `/api/operator/roles/${aId}/DEVELOPER`, { reason: "[QA] 공개 점검 개발자 부여" });
  const meA = await a.req("GET", "/api/auth/me");
  const denied = await Promise.all(["/api/operator/accounts", "/api/operator/usage", "/api/operator/roles"].map((p) => a.req("GET", p)));
  log(`운영자가 A 개발자 부여 ${brief(grant)} → A 같은 세션 roles ${JSON.stringify(meA.body?.roles)} | A 운영자 API ${denied.map(brief).join("·")}`);
  expect(grant.status === 200 && JSON.stringify(meA.body?.roles) === '["DEVELOPER"]' && denied.every((r) => r.status === 403), "개발자 부여·세션 반영·운영 API 차단이 예상과 달라 멈춤");

  const opSelf = await op.req("PUT", `/api/operator/roles/${opId}/DEVELOPER`, { reason: "운영자 자기 변경" });
  log(`운영자 자기 역할 변경 ${brief(opSelf)}`);
  expect(opSelf.status === 403 && opSelf.body?.code === "SELF_ROLE_CHANGE", "운영자 자기 변경이 막히지 않아 멈춤");

  const revoke = await op.req("DELETE", `/api/operator/roles/${aId}/DEVELOPER`, { reason: "[QA] 공개 점검 정리" });
  const meA2 = await a.req("GET", "/api/auth/me");
  const changes = ((await op.req("GET", "/api/operator/roles/changes")).body?.items ?? []).filter((c) => c.reason?.startsWith("[QA] 공개 점검"));
  log(`운영자가 개발자 회수 ${brief(revoke)} → A 같은 세션 roles ${JSON.stringify(meA2.body?.roles)} | 변경 기록 ${changes.length}건 (${changes.map((c) => c.action).join("·")})`);
  expect(revoke.status === 200 && meA2.body?.roles?.length === 0 && changes.length >= 2, "회수·기록이 예상과 달라 멈춤");
  log("확장 1단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (a.loggedIn) log("정리: A 탈퇴", brief(await a.req("POST", "/api/me/withdraw", { password: a.password })));
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  if (aId) {
    log("Neon SQL 편집기에서 확인 (A 의 남은 역할 0, 탈퇴 1, 변경 기록은 감사 기록이라 남음):");
    console.log(`select (select count(*) from account_role where account_id = ${aId}) as roles_left,
       (select count(*) from account where id = ${aId} and deleted_at is not null) as withdrawn,
       (select count(*) from account_role_change where target_account_id = ${aId}) as change_log;`);
  }
  process.exitCode = failed ? 1 : 0;
}
