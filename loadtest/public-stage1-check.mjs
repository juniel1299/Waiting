// 공개 배포의 1단계(회차 생성자 권한·정원 증설 승격·회차 물리 삭제)를 일반 가입 계정 3개로 확인하고 정리한다.
// 운영 환경에서 비밀번호로 로그인하는 단계가 있어, 사람이 직접 실행한다: node loadtest/public-stage1-check.mjs
// 비밀번호는 실행 중에만 무작위로 만들고 출력·저장하지 않는다. 가입은 3회 (IP 당 15분 5회 한도 안).
//
// 흐름: A 가입 → A 가 [QA] 회차(정원 1) 생성·수정·내가 만든 회차 조회 → B·C 가입 → B 예약, C 대기 → B 가 A 회차 관리 API 직접 호출 403
//      → A 명단 → A 정원 2 증설 → C 승격 → A 회차 삭제 → B·C 내 신청에서 사라짐·상세 404 → A·B·C 탈퇴.
// 남는 것이 없게: 계정마다 로그인 200 을 확인한 뒤에만 다음 단계로 가고, finally 에서 회차 삭제(남아 있으면)와 탈퇴를 한다.
// A 의 탈퇴는 A 가 만든 회차를 신청째 지우므로, 삭제 단계 전에 멈춰도 회차가 남지 않는다.
import { randomBytes } from "node:crypto";

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const WAKE_LIMIT_MS = 300_000;
const WEEK_MS = 7 * 24 * 3600 * 1000;

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
  async withdraw() {
    if (!this.loggedIn) return "로그인 전이라 건너뜀";
    const r = await this.req("POST", "/api/me/withdraw", { password: this.password });
    if (r.status === 204) this.loggedIn = false;
    return brief(r);
  }
}

function expect(cond, message) {
  if (!cond) throw new Error(message);
}

const summary = (s) => `${s.status} 확정 ${s.confirmedCount}/${s.capacity} 대기 ${s.waitingCount}`;
const a = new Visitor(`qa_a${tag}`);
const b = new Visitor(`qa_b${tag}`);
const c = new Visitor(`qa_c${tag}`);
let sessionId = null;
let deleted = false;
let failed = false;
try {
  log("A", a.loginId, await a.signupAndLogin());
  const created = await a.req("POST", "/api/sessions", {
    title: `[QA] 1단계 점검 ${tag}`,
    startsAt: new Date(Date.now() + WEEK_MS).toISOString(),
    location: "QA 점검실",
    capacity: 1,
  });
  expect(created.status === 201, `A 회차 생성 실패 ${brief(created)}`);
  sessionId = created.body.id;
  log(`A 회차 생성 201 /sessions/${sessionId}: ${summary(created.body)} canManage=${created.body.canManage} canDelete=${created.body.canDelete}`);
  const edited = await a.req("PATCH", `/api/sessions/${sessionId}`, {
    title: `[QA] 1단계 점검 ${tag} 수정`,
    startsAt: created.body.startsAt,
    location: "QA 점검실 2",
  });
  const mineSessions = await a.req("GET", `/api/sessions/search?scope=mine&q=${encodeURIComponent(`[QA] 1단계 점검 ${tag}`)}`);
  log(`A 본인 회차 수정 ${brief(edited)} "${edited.body?.title}" | 내가 만든 회차 검색 ${brief(mineSessions)} ${mineSessions.body?.totalElements}건`);
  expect(edited.status === 200 && edited.body.location === "QA 점검실 2" && mineSessions.body?.totalElements === 1, "본인 회차 수정·조회가 예상과 달라 멈춤");

  log("B", b.loginId, await b.signupAndLogin());
  log("C", c.loginId, await c.signupAndLogin());
  const rb = await b.req("POST", `/api/sessions/${sessionId}/reservations`);
  const rc = await c.req("POST", `/api/sessions/${sessionId}/waitlist`);
  log(`B 예약 ${brief(rb)} ${rb.body?.status}, C 대기 ${brief(rc)} ${rc.body?.status} ${rc.body?.waitingPosition}번`);
  expect(rb.body?.status === "CONFIRMED" && rc.body?.status === "WAITING", "예약·대기 신청이 예상과 달라 멈춤");

  const asB = await b.req("GET", `/api/sessions/${sessionId}`);
  log(`B 가 본 회차: canManage=${asB.body?.canManage} canDelete=${asB.body?.canDelete}`);
  const forbidden = [
    ["PATCH 수정", await b.req("PATCH", `/api/sessions/${sessionId}`, { title: "탈취", startsAt: created.body.startsAt, location: "x" })],
    ["PATCH 정원", await b.req("PATCH", `/api/sessions/${sessionId}/capacity`, { capacity: 5 })],
    ["GET 명단", await b.req("GET", `/api/sessions/${sessionId}/roster`)],
    ["DELETE 삭제", await b.req("DELETE", `/api/sessions/${sessionId}`)],
  ];
  log("B 의 관리 API 직접 호출:", forbidden.map(([n, r]) => `${n} ${brief(r)}`).join(", "));
  expect(forbidden.every(([, r]) => r.status === 403), "다른 회원의 관리 API 가 403 이 아니라 멈춤");

  const roster = await a.req("GET", `/api/sessions/${sessionId}/roster`);
  expect(roster.status === 200, `A 명단 조회 실패 ${brief(roster)}`);
  log(`A 명단 200: ${JSON.stringify(roster.body.map((r) => r.loginId))}`);

  const grow = await a.req("PATCH", `/api/sessions/${sessionId}/capacity`, { capacity: 2 });
  expect(grow.status === 200, `A 정원 증설 실패 ${brief(grow)}`);
  const cMine = (await c.mine()).filter((r) => r.sessionId === sessionId);
  log(`A 정원 2 증설 200: 승격 ${grow.body.promotedCount}명, ${summary(grow.body.session)} | C 내 신청: ${JSON.stringify(cMine.map((r) => r.status))}`);
  expect(grow.body.promotedCount === 1 && cMine[0]?.status === "CONFIRMED", "대기자 C 가 승격되지 않아 멈춤");

  const del = await a.req("DELETE", `/api/sessions/${sessionId}`);
  expect(del.status === 204, `A 회차 삭제 실패 ${brief(del)}`);
  deleted = true;
  // 물리 삭제: 취소(CANCELLED) 로 남는 게 아니라 신청 행 자체가 없어야 한다 (내 신청은 취소 이력도 돌려준다)
  const allOf = async (v) => (await v.req("GET", "/api/me/reservations")).body ?? [];
  const leftB = (await allOf(b)).some((r) => r.sessionId === sessionId);
  const leftC = (await allOf(c)).some((r) => r.sessionId === sessionId);
  const detail = await b.req("GET", `/api/sessions/${sessionId}`);
  log(`A 회차 삭제 204 | B 신청 행 남음(취소 포함): ${leftB}, C 신청 행 남음(취소 포함): ${leftC} | 상세 ${brief(detail)} "${detail.body?.message}"`);
  expect(!leftB && !leftC && detail.status === 404, "삭제한 회차가 남아 있어 멈춤");
  log("1단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (sessionId && !deleted && a.loggedIn) log(`정리: 회차 ${sessionId} 삭제`, brief(await a.req("DELETE", `/api/sessions/${sessionId}`)));
  for (const [name, v] of [["B", b], ["C", c], ["A", a]]) log(`정리: ${name} 탈퇴`, await v.withdraw());
  const leftovers = [a, b, c].filter((v) => v.loggedIn).map((v) => v.loginId);
  log(leftovers.length ? `남은 계정(탈퇴 실패): ${leftovers.join(", ")}` : "남은 로그인 계정 없음");
  process.exitCode = failed || leftovers.length ? 1 : 0;
}
