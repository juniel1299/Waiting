// 공개 점검 스크립트 공통 HTTP 클라이언트: 쿠키·CSRF 를 들고 다니고, 잠든 무료 서버가 깨는 동안(502/503/504) CSRF 를 다시 받는다.
// 테스트 회원 비밀번호는 실행 중에만 무작위로 만들고 출력하지 않는다.
import { randomBytes } from "node:crypto";

export const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const WAKE_LIMIT_MS = 300_000;
export const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const brief = (r) => `${r.status}${r.body?.code ? ` ${r.body.code}` : ""}`;

export function expect(cond, message) {
  if (!cond) throw new Error(message);
}

export class Client {
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
