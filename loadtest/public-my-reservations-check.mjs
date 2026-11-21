// 공개 배포의 '내 신청'을 일반 가입 계정 2개로 확인하고 정리한다. 소수 요청만 보낸다 (가입 최대 2회).
// 운영 환경에서 비밀번호로 로그인하는 단계가 있어, 사람이 직접 실행한다: node loadtest/public-my-reservations-check.mjs [회차id]
// 비밀번호는 실행 중에만 무작위로 만들고 출력·저장하지 않는다.
//
// 계정이 남지 않게: 한 계정씩 가입 → 로그인 성공을 확인한 뒤에만 다음 단계로 간다.
// 로그인에 실패하면 그 계정은 탈퇴할 수 없으므로(비밀번호를 남기지 않음) 즉시 멈추고 남은 아이디를 알린다.
import { randomBytes } from "node:crypto";

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const SESSION_ID = Number(process.argv[2] ?? 20); // 정원 1 운영 회차 (사용자가 만든 공개 회차)
const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const WAKE_LIMIT_MS = 300_000;

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const brief = (r) => `${r.status}${r.body?.code ? ` ${r.body.code}` : ""}`;

class Visitor {
  jar = new Map();
  loggedIn = false;
  constructor(loginId) {
    this.loginId = loginId;
    this.password = randomBytes(12).toString("base64url");
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
      else this.jar.delete(name); // 서버가 쿠키를 지우면(Max-Age=0) 따라 지운다
    }
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { json = null; }
    return { status: res.status, body: json };
  }
  /** CSRF 토큰. 잠든 무료 서버가 깨는 동안(502/503/504)은 5초 간격으로 최대 5분 다시 받는다. */
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
  async signupAndLogin() {
    const p = this.password;
    const s = await this.req("POST", "/api/auth/signup", { loginId: this.loginId, password: p, passwordConfirm: p });
    if (s.status !== 201) throw new Error(`${this.loginId} 가입 실패 ${brief(s)} (계정은 만들어지지 않음)`);
    const l = await this.req("POST", "/api/auth/login", { loginId: this.loginId, password: p });
    if (l.status !== 200) {
      throw new Error(`${this.loginId} 로그인 실패 ${brief(l)}: 가입된 이 계정은 탈퇴할 수 없어 남는다 (운영 DB 정리 필요)`);
    }
    this.loggedIn = true;
    this.jar.delete("XSRF-TOKEN"); // 로그인하면 토큰이 바뀐다
    return "가입 201 / 로그인 200";
  }
  async mine() {
    const r = await this.req("GET", "/api/me/reservations");
    if (r.status !== 200) throw new Error(`${this.loginId} 내 신청 조회 실패 ${brief(r)}`);
    return r.body.filter((x) => x.status !== "CANCELLED");
  }
  async session() {
    const r = await this.req("GET", `/api/sessions/${SESSION_ID}`);
    if (r.status !== 200) throw new Error(`회차 ${SESSION_ID} 조회 실패 ${brief(r)}`);
    return r.body;
  }
  async withdraw() {
    if (!this.loggedIn) return "로그인 전이라 건너뜀";
    const r = await this.req("POST", "/api/me/withdraw", { password: this.password });
    if (r.status === 204) this.loggedIn = false;
    return brief(r);
  }
}

const summary = (s) => `${s.status} 확정 ${s.confirmedCount}/${s.capacity} 대기 ${s.waitingCount}`;
const a = new Visitor(`qa_a${tag}`);
const b = new Visitor(`qa_b${tag}`);
let failed = false;
try {
  log("A", a.loginId, await a.signupAndLogin());
  const before = await a.session();
  log(`회차 ${SESSION_ID} 시작 전 (참가자 화면 값):`, summary(before));
  if (before.status !== "OPEN" || before.capacity - before.confirmedCount !== 1 || before.waitingCount !== 0) {
    throw new Error("남은 자리 1·대기 0 인 시작 전 회차가 아니라 신청 전에 멈춤");
  }
  log("B", b.loginId, await b.signupAndLogin());
  const rb = await b.req("POST", `/api/sessions/${SESSION_ID}/reservations`);
  const ra = await a.req("POST", `/api/sessions/${SESSION_ID}/waitlist`);
  log(`B 예약 ${brief(rb)} ${rb.body?.status}, A 대기 ${brief(ra)} ${ra.body?.status} ${ra.body?.waitingPosition}번`);
  if (rb.status !== 201 || ra.status !== 201) throw new Error("예약·대기 신청이 예상과 달라 멈춤");
  const [mineA, mineB] = [await a.mine(), await b.mine()];
  log("A 내 신청:", JSON.stringify(mineA.map((r) => [r.id, r.sessionTitle, r.location, r.status, r.waitingPosition])));
  log("B 내 신청:", JSON.stringify(mineB.map((r) => [r.id, r.sessionTitle, r.location, r.status, r.waitingPosition])));
  log("A 목록에 B 신청 없음:", !mineA.some((r) => r.id === rb.body.id), "| B 목록에 A 신청 없음:", !mineB.some((r) => r.id === ra.body.id));
  const cancel = await b.req("POST", `/api/reservations/${rb.body.id}/cancel`);
  log(`B 취소 ${brief(cancel)} 뒤 A 내 신청:`, JSON.stringify((await a.mine()).map((r) => [r.id, r.status])));
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  // A 를 먼저 탈퇴시켜(남은 신청 취소) B 로 회차의 최종 상태를 본 뒤 B 도 탈퇴한다
  log("정리: A 탈퇴", await a.withdraw());
  if (b.loggedIn) {
    try {
      log(`회차 ${SESSION_ID} 정리 뒤 (참가자 화면 값):`, summary(await b.session()));
    } catch (e) {
      log("정리 뒤 회차 조회 실패:", e.message);
    }
  }
  log("정리: B 탈퇴", await b.withdraw());
  const leftovers = [a, b].filter((v) => v.loggedIn).map((v) => v.loginId);
  log(leftovers.length ? `남은 계정(탈퇴 실패): ${leftovers.join(", ")}` : "남은 로그인 계정 없음");
  process.exitCode = failed || leftovers.length ? 1 : 0;
}
