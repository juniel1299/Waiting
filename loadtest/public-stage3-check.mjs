// 공개 배포의 3단계(운영자 계정 관리·사용량 대시보드)를 전용 테스트 계정으로 확인하고 정리한다.
// 운영자 비밀번호가 필요해 사람이 직접 실행한다: node loadtest/public-stage3-check.mjs
// 운영자 아이디·비밀번호는 실행할 때 입력받는다(비밀번호는 화면에 보이지 않음). 출력·저장하지 않는다.
// 테스트 회원 비밀번호는 실행 중에만 무작위로 만든다. 가입은 2회 (IP 당 15분 5회 한도 안).
//
// 흐름: 회원 A·B 가입 → 운영자가 [QA] 회차(정원 1) 생성 → A 예약, B 대기, A 가 [QA] 회차 하나 생성
//      → 운영자: A 검색 → 영향 미리보기(확정 1·승격 1·만든 회차 1) → 정지(사유) → A 기존 세션 401·재로그인 403
//      → 해제 → A 재로그인 200 → 강제탈퇴 → B 확정 승격·A 회차 삭제 → 조치 기록(익명 표시) → 대시보드 응답
// 정리(finally): 운영자 [QA] 회차 삭제, B 탈퇴(로그인 못 했으면 운영자가 강제탈퇴), A 가 남아 있으면 강제탈퇴.
// 조치 기록은 감사 기록이라 남는다 (대상은 익명화된 계정 id 만 가리킨다). 조치 대상은 이 스크립트가 만든 계정뿐이다.
import { randomBytes } from "node:crypto";
import { createInterface } from "node:readline";

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const WAKE_LIMIT_MS = 300_000;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const brief = (r) => `${r.status}${r.body?.code ? ` ${r.body.code}` : ""}`;

/** 운영자 아이디·비밀번호를 묻는다. 비밀번호 입력 글자는 화면에 쓰지 않는다. */
async function askOperator() {
  if (!process.stdin.isTTY) {
    // 파이프 입력(로컬 리허설): 첫 줄 아이디, 둘째 줄 비밀번호. 아무것도 화면에 쓰지 않는다
    const lines = [];
    for await (const line of createInterface({ input: process.stdin })) {
      lines.push(line);
      if (lines.length === 2) break;
    }
    return lines;
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  let hide = false;
  const write = rl._writeToOutput.bind(rl);
  rl._writeToOutput = (text) => (hide ? undefined : write(text));
  const question = (q) => new Promise((resolve) => rl.question(q, resolve));
  const loginId = await question("운영자 아이디: ");
  process.stdout.write("운영자 비밀번호(화면에 보이지 않음): ");
  hide = true;
  const password = await question("");
  rl.close();
  process.stdout.write("\n");
  return [loginId, password];
}

class Client {
  jar = new Map();
  loggedIn = false;
  constructor(loginId, password = randomBytes(12).toString("base64url")) {
    this.loginId = loginId;
    this.password = password;
  }
  async req(method, path, body) {
    if (method !== "GET" && !this.jar.get("XSRF-TOKEN")) await this.csrf();
    const headers = { cookie: [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.jar.get("XSRF-TOKEN");
    if (body) headers["content-type"] = "application/json";
    const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      const [name, value] = [pair.slice(0, i), pair.slice(i + 1)];
      if (value) this.jar.set(name, value);
      else this.jar.delete(name);
    }
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { json = null; }
    return { status: res.status, body: json };
  }
  /** 잠든 무료 서버가 깨는 동안(502/503/504)은 5초 간격으로 최대 5분 다시 받는다. */
  async csrf() {
    const deadline = Date.now() + WAKE_LIMIT_MS;
    for (;;) {
      const r = await this.req("GET", "/api/auth/csrf");
      if (r.status === 200 && this.jar.get("XSRF-TOKEN")) return;
      if (![502, 503, 504].includes(r.status) || Date.now() > deadline) throw new Error(`CSRF 토큰을 받지 못함: ${brief(r)}`);
      log(`서버가 깨는 중 (${r.status}), 5초 뒤 다시`);
      await sleep(5000);
    }
  }
  async login() {
    const r = await this.req("POST", "/api/auth/login", { loginId: this.loginId, password: this.password });
    this.loggedIn = r.status === 200;
    if (this.loggedIn) this.jar.delete("XSRF-TOKEN"); // 로그인하면 토큰이 바뀐다
    return r;
  }
  async signupAndLogin() {
    const p = this.password;
    const s = await this.req("POST", "/api/auth/signup", { loginId: this.loginId, password: p, passwordConfirm: p });
    if (s.status !== 201) throw new Error(`${this.loginId} 가입 실패 ${brief(s)} (계정은 만들어지지 않음)`);
    this.signedUp = true;
    const l = await this.login();
    if (l.status !== 200) throw new Error(`${this.loginId} 로그인 실패 ${brief(l)}: 정리 단계에서 운영자가 강제탈퇴한다`);
    return "가입 201 / 로그인 200";
  }
}

function expect(cond, message) {
  if (!cond) throw new Error(message);
}

const op = new Client(...(await askOperator()));
const a = new Client(`qa_ma${tag}`);
const b = new Client(`qa_mb${tag}`);
let aId = null;
let aWithdrawn = false;
let qaSession = null;
let failed = false;
const inAWeek = () => new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();
const moderate = (id, action, reason) => op.req("POST", `/api/operator/accounts/${id}/${action}`, { reason });
const accountIdOf = async (loginId) =>
  (await op.req("GET", `/api/operator/accounts?q=${encodeURIComponent(loginId)}`)).body?.items?.find((x) => x.loginId === loginId)?.id ?? null;

try {
  const ol = await op.login();
  op.password = null; // 로그인 뒤에는 들고 있지 않는다
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  log("A", a.loginId, await a.signupAndLogin());
  log("B", b.loginId, await b.signupAndLogin());

  const created = await op.req("POST", "/api/operator/sessions", { title: `[QA] 3단계 점검 ${tag}`, startsAt: inAWeek(), location: "QA 점검실", capacity: 1 });
  expect(created.status === 201, `운영자 [QA] 회차 생성 실패 ${brief(created)}`);
  qaSession = created.body.id;
  const ra = await a.req("POST", `/api/sessions/${qaSession}/reservations`);
  const rb = await b.req("POST", `/api/sessions/${qaSession}/waitlist`);
  const own = await a.req("POST", "/api/sessions", { title: `[QA] 3단계 A 회차 ${tag}`, startsAt: inAWeek(), location: "QA 점검실", capacity: 2 });
  log(`[QA] 회차 ${qaSession}: A 예약 ${brief(ra)}, B 대기 ${brief(rb)} | A 회차 생성 ${brief(own)} /sessions/${own.body?.id}`);
  expect(ra.status === 201 && rb.status === 201 && own.status === 201, "준비 단계가 예상과 달라 멈춤");

  const found = await op.req("GET", `/api/operator/accounts?q=${encodeURIComponent(a.loginId)}`);
  expect(found.status === 200 && found.body.totalElements === 1, `A 검색 실패 ${brief(found)}`);
  aId = found.body.items[0].id;
  const i = (await op.req("GET", `/api/operator/accounts/${aId}/impact`)).body;
  log(`A 검색 200 (계정 #${aId}) | 영향: 시작 전 확정 ${i?.upcomingConfirmed}, 승격 ${i?.promotions}, 대기 ${i?.waiting}, 만든 회차 ${i?.ownedSessions}(신청 ${i?.ownedSessionReservations})`);
  expect(i?.upcomingConfirmed === 1 && i.promotions === 1 && i.ownedSessions === 1, "영향 미리보기가 예상과 달라 멈춤");

  const noReason = await moderate(aId, "suspend", "  ");
  const suspend = await moderate(aId, "suspend", "공개 점검: 정지 확인");
  const aSession = await a.req("GET", "/api/me/reservations");
  const aRelogin = await a.login();
  log(`사유 없는 정지 ${brief(noReason)} | 정지 ${brief(suspend)} → A 기존 세션 ${brief(aSession)}, A 재로그인 ${brief(aRelogin)}`);
  expect(noReason.status === 400 && suspend.status === 200 && aSession.status === 401 && aRelogin.body?.code === "ACCOUNT_SUSPENDED", "정지 차단이 예상과 달라 멈춤");

  const lift = await moderate(aId, "unsuspend", "공개 점검: 해제 확인");
  const aBack = await a.login();
  log(`해제 ${brief(lift)} → A 재로그인 ${brief(aBack)}`);
  expect(lift.status === 200 && aBack.status === 200, "해제가 예상과 달라 멈춤");

  const withdraw = await moderate(aId, "withdraw", "공개 점검: 강제탈퇴 확인");
  aWithdrawn = withdraw.status === 200;
  const bMine = (await b.req("GET", "/api/me/reservations")).body ?? [];
  const ownAfter = await b.req("GET", `/api/sessions/${own.body.id}`);
  const aAfter = await a.req("GET", "/api/me/reservations");
  log(`강제탈퇴 ${brief(withdraw)} ${withdraw.body?.status} → B 신청 ${JSON.stringify(bMine.filter((r) => r.sessionId === qaSession).map((r) => r.status))}, A 회차 ${brief(ownAfter)}, A 세션 ${brief(aAfter)}`);
  expect(aWithdrawn && bMine.some((r) => r.sessionId === qaSession && r.status === "CONFIRMED") && ownAfter.status === 404 && aAfter.status === 401, "강제탈퇴 결과가 예상과 달라 멈춤");

  const actions = await op.req("GET", `/api/operator/actions?targetId=${aId}`);
  log("조치 기록:", JSON.stringify(actions.body?.items?.map((x) => [x.action, x.targetLabel, x.reason, x.cancelledReservations, x.deletedSessions])));
  expect(actions.body?.items?.length === 3 && actions.body.items.every((x) => x.targetLabel === `탈퇴한 회원 #${aId}`), "조치 기록이 예상과 달라 멈춤");

  const usage = await op.req("GET", "/api/operator/usage");
  log(`대시보드 ${brief(usage)} 집계 시작 ${usage.body?.countingStartedAt} | 현재 일반 회차 ${usage.body?.current?.regular?.sessions}, 정지 계정 ${usage.body?.current?.regular?.suspended}`);
  expect(usage.status === 200, "대시보드 조회 실패");
  log("3단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (op.loggedIn) {
    if (qaSession) log(`정리: [QA] 회차 ${qaSession} 삭제`, brief(await op.req("DELETE", `/api/sessions/${qaSession}`)));
    if (b.loggedIn) log("정리: B 탈퇴", brief(await b.req("POST", "/api/me/withdraw", { password: b.password })));
    else if (b.signedUp) log("정리: B 강제탈퇴", brief(await moderate(await accountIdOf(b.loginId), "withdraw", "공개 점검 정리")));
    if (!aWithdrawn && a.signedUp) log("정리: A 강제탈퇴", brief(await moderate(aId ?? (await accountIdOf(a.loginId)), "withdraw", "공개 점검 정리")));
    log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  }
  process.exitCode = failed ? 1 : 0;
}
