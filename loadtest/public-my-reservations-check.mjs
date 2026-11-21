// 공개 배포의 '내 신청'을 일반 가입 계정으로 확인하고 정리한다. 소수 요청만 보낸다 (가입 3회, 탈퇴 3회).
// 운영 환경에서 비밀번호로 로그인하는 단계가 있어, 사람이 직접 실행한다: node loadtest/public-my-reservations-check.mjs [회차id]
// 비밀번호는 실행 중에만 무작위로 만들고 출력·저장하지 않는다.
import { randomBytes } from "node:crypto";

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const SESSION_ID = Number(process.argv[2] ?? 20); // 정원 1 운영 회차 (사용자가 만든 공개 회차)
const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");

class Visitor {
  jar = new Map();
  constructor(loginId) {
    this.loginId = loginId;
    this.password = randomBytes(12).toString("base64url");
  }
  async req(method, path, body) {
    if (method !== "GET" && !this.jar.get("XSRF-TOKEN")) await this.req("GET", "/api/auth/csrf");
    const headers = { cookie: [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.jar.get("XSRF-TOKEN");
    if (body) headers["content-type"] = "application/json";
    const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      this.jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { json = null; }
    return { status: res.status, body: json };
  }
  async signupAndLogin() {
    const p = this.password;
    const s = await this.req("POST", "/api/auth/signup", { loginId: this.loginId, password: p, passwordConfirm: p });
    const l = await this.req("POST", "/api/auth/login", { loginId: this.loginId, password: p });
    this.jar.delete("XSRF-TOKEN"); // 로그인하면 토큰이 바뀐다
    return `${s.status}/${l.status}`;
  }
  async mine() {
    const r = await this.req("GET", "/api/me/reservations");
    return (r.body ?? []).filter((x) => x.status !== "CANCELLED");
  }
  async withdraw() {
    return (await this.req("POST", "/api/me/withdraw", { password: this.password })).status;
  }
}

const summary = (s) => `${s.status} 확정 ${s.confirmedCount}/${s.capacity} 대기 ${s.waitingCount}`;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const a = new Visitor(`qa_a${tag}`);
const b = new Visitor(`qa_b${tag}`);
const c = new Visitor(`qa_c${tag}`);
try {
  log("가입·로그인 A", await a.signupAndLogin(), "B", await b.signupAndLogin());
  const before = (await a.req("GET", `/api/sessions/${SESSION_ID}`)).body;
  log(`회차 ${SESSION_ID} 시작 전 (참가자 화면 값):`, summary(before));
  if (before.status !== "OPEN" || before.capacity - before.confirmedCount !== 1 || before.waitingCount !== 0) {
    throw new Error("남은 자리 1·대기 0 인 시작 전 회차가 아니라 멈춤");
  }
  const rb = await b.req("POST", `/api/sessions/${SESSION_ID}/reservations`);
  const ra = await a.req("POST", `/api/sessions/${SESSION_ID}/waitlist`);
  log(`B 예약 ${rb.status} ${rb.body?.status}, A 대기 ${ra.status} ${ra.body?.status} ${ra.body?.waitingPosition}번`);
  const [mineA, mineB] = [await a.mine(), await b.mine()];
  log("A 내 신청:", JSON.stringify(mineA.map((r) => [r.id, r.sessionTitle, r.location, r.status, r.waitingPosition])));
  log("B 내 신청:", JSON.stringify(mineB.map((r) => [r.id, r.sessionTitle, r.location, r.status, r.waitingPosition])));
  log("A 목록에 B 신청 없음:", !mineA.some((r) => r.id === rb.body?.id), "| B 목록에 A 신청 없음:", !mineB.some((r) => r.id === ra.body?.id));
  await b.req("POST", `/api/reservations/${rb.body.id}/cancel`);
  log("B 취소 뒤 A 내 신청:", JSON.stringify((await a.mine()).map((r) => [r.id, r.status])));
} catch (e) {
  log("중단:", e.message);
} finally {
  log("정리: A 탈퇴", await a.withdraw(), "B 탈퇴", await b.withdraw());
  log("확인용 C 가입·로그인", await c.signupAndLogin());
  const after = (await c.req("GET", `/api/sessions/${SESSION_ID}`)).body;
  log(`회차 ${SESSION_ID} 정리 뒤 (참가자 화면 값):`, after ? summary(after) : "조회 실패");
  log("C 탈퇴", await c.withdraw());
}
