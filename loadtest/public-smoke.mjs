// 공개 배포 소수 요청 점검: 데모 흐름 3회 순차 + 데모 시작 3건 동시. 부하가 아니라 정상 흐름·응답 시간 확인용이다.
// 데모 시작은 IP 당 15분 10회 한도라 한 번 실행에 6회만 쓴다. 사용: node loadtest/public-smoke.mjs
import { performance } from "node:perf_hooks";

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";

class Client {
  cookies = new Map();
  async request(method, path) {
    // 로그인·체험 시작은 CSRF 토큰을 새로 바꾸고 쿠키를 비운다: 프런트처럼 비었으면 먼저 다시 받는다
    if (method !== "GET" && !this.cookies.get("XSRF-TOKEN")) await this.request("GET", "/api/auth/csrf");
    const headers = { cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.cookies.get("XSRF-TOKEN") ?? "";
    const started = performance.now();
    const res = await fetch(BASE + path, { method, headers, signal: AbortSignal.timeout(330_000) });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      this.cookies.set(pair.slice(0, i), pair.slice(i + 1));
    }
    const text = await res.text();
    let body = null;
    try { body = JSON.parse(text); } catch { body = null; }
    return { path, method, status: res.status, body, ms: Math.round(performance.now() - started) };
  }
}

/** 방문자 한 명의 데모 흐름: 체험 시작 → 회차 조회 → 대기 신청 → 샘플 취소 → 회차 조회(확정 확인). */
async function demoFlow() {
  const c = new Client();
  const steps = [];
  steps.push(await c.request("GET", "/api/auth/csrf"));
  const start = await c.request("POST", "/api/demo/start");
  steps.push(start);
  const sid = start.body?.sessionId;
  steps.push(await c.request("GET", `/api/sessions/${sid}`));
  steps.push(await c.request("POST", `/api/sessions/${sid}/waitlist`));
  steps.push(await c.request("POST", "/api/demo/sample-cancel"));
  const after = await c.request("GET", `/api/sessions/${sid}`);
  steps.push(after);
  const ok = start.status === 201 && after.body?.myReservation?.status === "CONFIRMED";
  return { ok, sid, steps };
}

const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)]; };
const all = [];
console.log(`시작 ${new Date().toISOString()} ${BASE}`);
for (let i = 1; i <= 3; i++) {
  const f = await demoFlow();
  all.push(...f.steps);
  console.log(`순차 ${i}: ${f.ok ? "확정까지 정상" : "실패"} /sessions/${f.sid} | ${f.steps.map((s) => `${s.method} ${s.path.replace(/\d+/, ":id")} ${s.status} ${s.ms}ms`).join(" | ")}`);
}
const t = performance.now();
const concurrent = await Promise.all([1, 2, 3].map(async () => {
  const c = new Client();
  await c.request("GET", "/api/auth/csrf");
  return c.request("POST", "/api/demo/start");
}));
all.push(...concurrent);
console.log(`동시 3건 데모 시작: ${concurrent.map((r) => `${r.status} ${r.ms}ms`).join(", ")} (전체 ${Math.round(performance.now() - t)}ms)`);
const ms = all.map((s) => s.ms);
const statuses = all.reduce((m, s) => ({ ...m, [s.status]: (m[s.status] ?? 0) + 1 }), {});
console.log(`요청 ${all.length}건 상태 ${JSON.stringify(statuses)} | p50 ${pct(ms, 50)}ms p95 ${pct(ms, 95)}ms max ${Math.max(...ms)}ms`);
