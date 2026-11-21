// 마지막 자리·취소/승격 동시성 부하 검증. 의존성 없이 Node 내장 fetch(undici)만 쓴다.
// 사용: node loadtest/loadtest.mjs  (환경변수 BASE_URL, LEVELS, PSQL — run.sh 가 넣어 준다)
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { performance } from "node:perf_hooks";

const BASE = process.env.BASE_URL ?? "http://localhost:18090";
const LEVELS = (process.env.LEVELS ?? "10,50,100,200,400").split(",").map(Number);
// 실행할 시나리오 (A 마지막 자리, B 취소·승격, C 정원 증설). 예: SCENARIOS=C
const SCENARIOS = (process.env.SCENARIOS ?? "A,B,C").split(",");
const ADDED_SEATS = 5; // 시나리오 C 증설 폭
const PASSWORD = "loadtest-pass-1!";
const HOLDERS = 5; // 시나리오 B 회차 정원
const RUN_ID = new Date().toISOString().replace(/[:.]/g, "-");

// ---- psql (docker compose 의 postgres 컨테이너) ----
const PSQL = (process.env.PSQL ?? "docker compose exec -T postgres psql -U daumjari -d daumjari_load").split(" ");
function sql(query) {
  return execFileSync(PSQL[0], [...PSQL.slice(1), "-qAt", "-v", "ON_ERROR_STOP=1", "-c", query], { encoding: "utf8" }).trim();
}

// ---- 쿠키를 들고 다니는 최소 클라이언트 (JSESSIONID, XSRF-TOKEN) ----
class Client {
  cookies = new Map();
  async request(method, path, body) {
    const headers = { cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.cookies.get("XSRF-TOKEN") ?? "";
    if (body !== undefined) headers["content-type"] = "application/json";
    const started = performance.now();
    let status = 0;
    let code = "";
    let id = null;
    let waitingPosition = null;
    try {
      const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
      for (const c of res.headers.getSetCookie()) {
        const [pair] = c.split(";");
        const i = pair.indexOf("=");
        this.cookies.set(pair.slice(0, i), pair.slice(i + 1));
      }
      status = res.status;
      const text = await res.text();
      try {
        const json = JSON.parse(text);
        code = json.code ?? json.status ?? "";
        id = json.id ?? null;
        waitingPosition = json.waitingPosition ?? null;
      } catch {
        code = "";
      }
    } catch (e) {
      code = e.name === "TimeoutError" ? "TIMEOUT" : `NETWORK:${e.cause?.code ?? e.message}`;
    }
    return { status, code, id, waitingPosition, startedAt: started, ms: performance.now() - started };
  }
  async csrf() {
    await this.request("GET", "/api/auth/csrf");
  }
}

async function pool(items, limit, fn) {
  const out = [];
  let next = 0;
  await Promise.all(Array.from({ length: limit }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  }));
  return out;
}

async function prepareUsers(count, prefix = "lt") {
  const ids = Array.from({ length: count }, (_, i) => `${prefix}_${RUN_ID.slice(11, 19).replace(/-/g, "")}_${i}`.toLowerCase().slice(0, 20));
  return pool(ids, 16, async (loginId) => {
    const c = new Client();
    await c.csrf();
    const s = await c.request("POST", "/api/auth/signup", { loginId, password: PASSWORD, passwordConfirm: PASSWORD });
    if (s.status !== 201) throw new Error(`signup ${loginId}: ${s.status} ${s.code}`);
    const l = await c.request("POST", "/api/auth/login", { loginId, password: PASSWORD });
    if (l.status !== 200) throw new Error(`login ${loginId}: ${l.status} ${l.code}`);
    await c.csrf(); // 로그인하면 토큰이 재발급된다
    return c;
  });
}

function newSession(title, capacity) {
  return Number(sql(`insert into study_session (title, starts_at, location, capacity, created_at)
    values ('${title}', now() + interval '7 days', 'loadtest', ${capacity}, now()) returning id`));
}

/** 모든 요청을 같은 순간에 출발시켜(준비 끝난 뒤 한꺼번에) 결과와 지연을 모은다. */
async function burst(requests) {
  const started = performance.now();
  const results = await Promise.all(requests.map(({ client, method, path, body, expect, label }) =>
    client.request(method, path, body).then((r) => ({ ...r, label, expected: expect.includes(`${r.status} ${r.code}`) }))));
  return { wallMs: performance.now() - started, results };
}

function summarize(name, n, { wallMs, results }) {
  const ms = results.map((r) => r.ms).sort((a, b) => a - b);
  const pct = (p) => ms[Math.min(ms.length - 1, Math.ceil((p / 100) * ms.length) - 1)];
  const byCode = {};
  for (const r of results) byCode[`${r.label} ${r.status} ${r.code}`] = (byCode[`${r.label} ${r.status} ${r.code}`] ?? 0) + 1;
  const unexpected = results.filter((r) => !r.expected);
  return {
    scenario: name, concurrency: n, requests: results.length, wallMs: Math.round(wallMs),
    throughputRps: Math.round((results.length / wallMs) * 1000),
    p50Ms: Math.round(pct(50)), p95Ms: Math.round(pct(95)), maxMs: Math.round(ms.at(-1)),
    responses: byCode, unexpected: unexpected.length,
    unexpectedDetail: [...new Set(unexpected.map((r) => `${r.label} ${r.status} ${r.code}`))],
  };
}

// 시나리오 A: 정원 1 회차에 N명 동시 예약 → 정확히 1명 확정, 나머지 409 SESSION_FULL
async function scenarioA(users, n) {
  const sid = newSession(`LOAD-A n=${n}`, 1);
  const out = await burst(users.slice(0, n).map((client) => ({ client, method: "POST", path: `/api/sessions/${sid}/reservations`, label: "reserve", expect: ["201 CONFIRMED", "409 SESSION_FULL"] })));
  const s = summarize("A 마지막 자리", n, out);
  s.sessionId = sid;
  s.confirmedInDb = Number(sql(`select count(*) from reservation where study_session_id = ${sid} and status = 'CONFIRMED'`));
  s.pass = s.unexpected === 0 && s.confirmedInDb === 1 && (s.responses["reserve 201 CONFIRMED"] ?? 0) === 1;
  return s;
}

// 시나리오 B: 정원 5 마감 회차에 N명 동시 대기 → 확정자 5명 취소 + 신규 예약 N/4 + 신규 대기 N/4 를 동시에
// → 먼저 대기한 5명(id 순)이 승격, 신규 예약은 대기자가 있어 409, 신규 대기는 맨 뒤.
async function scenarioB(users, n) {
  const sid = newSession(`LOAD-B n=${n}`, HOLDERS);
  const holders = users.slice(0, HOLDERS);
  const waiters = users.slice(HOLDERS, HOLDERS + n);
  const quarter = Math.max(1, Math.floor(n / 4));
  const newcomers = users.slice(HOLDERS + n, HOLDERS + n + quarter * 2);
  const held = await Promise.all(holders.map((c) => c.request("POST", `/api/sessions/${sid}/reservations`)));
  if (held.some((r) => r.status !== 201)) throw new Error(`holders: ${held.map((r) => r.status)}`);

  const wait = await burst(waiters.map((client) => ({ client, method: "POST", path: `/api/sessions/${sid}/waitlist`, label: "waitlist", expect: ["201 WAITING"] })));
  const b1 = summarize("B1 동시 대기", n, wait);
  const firstWaiters = sql(`select id from reservation where study_session_id = ${sid} and status = 'WAITING' order by id limit ${HOLDERS}`).split("\n");

  // 확정자 취소·신규 예약·신규 대기를 한꺼번에 출발시킨다
  const mixed = [];
  const myReservationId = async (client) => {
    const res = await fetch(BASE + "/api/me/reservations", { headers: { cookie: [...client.cookies].map(([k, v]) => `${k}=${v}`).join("; ") } });
    return (await res.json()).find((r) => r.sessionId === sid && r.status === "CONFIRMED").id;
  };
  for (const client of holders) mixed.push({ client, method: "POST", path: `/api/reservations/${await myReservationId(client)}/cancel`, label: "cancel", expect: ["200 CANCELLED"] });
  newcomers.slice(0, quarter).forEach((client) => mixed.push({ client, method: "POST", path: `/api/sessions/${sid}/reservations`, label: "reserve", expect: ["409 SESSION_FULL"] }));
  newcomers.slice(quarter).forEach((client) => mixed.push({ client, method: "POST", path: `/api/sessions/${sid}/waitlist`, label: "waitlist", expect: ["201 WAITING"] }));
  const b2 = summarize("B2 취소·승격 혼합", mixed.length, await burst(mixed));

  const promoted = sql(`select id from reservation where study_session_id = ${sid} and status = 'CONFIRMED' order by id`).split("\n");
  const waitingLeft = Number(sql(`select count(*) from reservation where study_session_id = ${sid} and status = 'WAITING'`));
  const promotedInOrder = JSON.stringify(promoted) === JSON.stringify(firstWaiters);
  for (const s of [b1, b2]) s.sessionId = sid;
  b2.promotedIds = promoted;
  b2.expectedPromotedIds = firstWaiters;
  b2.promotedInOrder = promotedInOrder;
  b2.waitingLeft = waitingLeft;
  b1.pass = b1.unexpected === 0;
  b2.pass = b2.unexpected === 0 && promotedInOrder && waitingLeft === n - HOLDERS + quarter;
  return [b1, b2];
}

// 시나리오 C: 정원 1(확정자 1명) 회차에 N명 대기(동시) → 다음을 한꺼번에:
//   운영자 정원 증설 1→1+5, 운영자 정원 축소 →1(순서에 따라 200 또는 409 CAPACITY_BELOW_CONFIRMED),
//   확정자 취소, 신규 예약 N/4, 신규 대기 N/4
// → 정원은 어느 순서든 6 으로 끝나고, 먼저 대기한 6명(대기 순번 1~6)이 확정, 신규 예약은 대기자가 있어 409.
async function scenarioC(users, operator, n) {
  const capacity = 1 + ADDED_SEATS;
  const sid = newSession(`LOAD-C n=${n}`, 1);
  const holder = users[0];
  const waiters = users.slice(1, 1 + n);
  const quarter = Math.max(1, Math.floor(n / 4));
  const newcomers = users.slice(1 + n, 1 + n + quarter * 2);
  const held = await holder.request("POST", `/api/sessions/${sid}/reservations`);
  if (held.status !== 201) throw new Error(`holder ${held.status} ${held.code}`);

  const wait = await burst(waiters.map((client) => ({ client, method: "POST", path: `/api/sessions/${sid}/waitlist`, label: "waitlist", expect: ["201 WAITING"] })));
  const c1 = summarize("C1 동시 대기", n, wait);
  // 대기자 우선 근거: 요청 출발 순서·응답 시간과 DB 가 매긴 id·대기 순번
  const queue = wait.results
    .map((r, sendIndex) => ({ sendIndex, startedMs: Math.round(r.startedAt - wait.results[0].startedAt), responseMs: Math.round(r.ms), id: r.id, position: r.waitingPosition }))
    .sort((a, b) => a.id - b.id);

  const mixed = [
    { client: operator, method: "PATCH", path: `/api/operator/sessions/${sid}/capacity`, body: { capacity }, label: "capacity+", expect: ["200 "] },
    { client: operator, method: "PATCH", path: `/api/operator/sessions/${sid}/capacity`, body: { capacity: 1 }, label: "capacity-", expect: ["200 ", "409 CAPACITY_BELOW_CONFIRMED"] },
    { client: holder, method: "POST", path: `/api/reservations/${held.id}/cancel`, label: "cancel", expect: ["200 CANCELLED"] },
  ];
  newcomers.slice(0, quarter).forEach((client) => mixed.push({ client, method: "POST", path: `/api/sessions/${sid}/reservations`, label: "reserve", expect: ["409 SESSION_FULL"] }));
  newcomers.slice(quarter).forEach((client) => mixed.push({ client, method: "POST", path: `/api/sessions/${sid}/waitlist`, label: "waitlist", expect: ["201 WAITING"] }));
  const c2 = summarize("C2 증설·축소·취소 혼합", mixed.length, await burst(mixed));

  const finalCapacity = Number(sql(`select capacity from study_session where id = ${sid}`));
  const confirmedIds = sql(`select id from reservation where study_session_id = ${sid} and status = 'CONFIRMED' order by id`).split("\n").map(Number);
  const expected = queue.slice(0, capacity).map((q) => q.id);
  const promotedInOrder = JSON.stringify(confirmedIds) === JSON.stringify(expected);
  const waitingLeft = Number(sql(`select count(*) from reservation where study_session_id = ${sid} and status = 'WAITING'`));
  for (const x of [c1, c2]) x.sessionId = sid;
  c1.queue = queue;
  c1.pass = c1.unexpected === 0 && queue.every((q, i) => q.position === i + 1);
  Object.assign(c2, { finalCapacity, confirmedIds, expectedConfirmedIds: expected, promotedInOrder, waitingLeft,
    promotedPositions: queue.filter((q) => confirmedIds.includes(q.id)).map((q) => q.position) });
  c2.pass = c2.unexpected === 0 && finalCapacity === capacity && promotedInOrder && waitingLeft === n - capacity + quarter;
  return [c1, c2];
}

/** 운영자: 참가자로 가입·로그인한 계정을 SQL 로 OPERATOR 로 바꾼 뒤 다시 로그인한다 (시드 비밀번호를 쓰지 않음). */
async function prepareOperator() {
  const [op] = await prepareUsers(1, "op");
  const loginId = sql(`select login_id from account order by id desc limit 1`);
  sql(`update account set role = 'OPERATOR' where login_id = '${loginId}'`);
  const relogin = await op.request("POST", "/api/auth/login", { loginId, password: PASSWORD });
  if (relogin.status !== 200) throw new Error(`operator login ${relogin.status}`);
  await op.csrf();
  return op;
}

const maxN = Math.max(...LEVELS);
const t0 = performance.now();
console.log(`준비: 계정 ${HOLDERS + maxN + Math.floor(maxN / 4) * 2}개 가입·로그인 (${BASE})`);
const users = await prepareUsers(HOLDERS + maxN + Math.floor(maxN / 4) * 2);
const operator = SCENARIOS.includes("C") ? await prepareOperator() : null;
console.log(`준비 끝 ${Math.round(performance.now() - t0)}ms`);
const summary = [];
for (const n of LEVELS) {
  const results = [];
  if (SCENARIOS.includes("A")) results.push(await scenarioA(users, n));
  if (SCENARIOS.includes("B")) results.push(...(await scenarioB(users, n)));
  if (SCENARIOS.includes("C")) results.push(...(await scenarioC(users, operator, n)));
  for (const s of results) {
    summary.push(s);
    console.log(`${s.pass ? "PASS" : "FAIL"} ${s.scenario} N=${s.concurrency} req=${s.requests} wall=${s.wallMs}ms ${s.throughputRps}req/s p50=${s.p50Ms} p95=${s.p95Ms} max=${s.maxMs} unexpected=${s.unexpected} ${JSON.stringify(s.responses)}`);
  }
}
mkdirSync("loadtest/results", { recursive: true });
const file = `loadtest/results/${RUN_ID}.json`;
writeFileSync(file, JSON.stringify({ runId: RUN_ID, base: BASE, levels: LEVELS, scenarios: SCENARIOS, summary }, null, 2));
console.log(`결과 저장: ${file}`);
process.exitCode = summary.every((s) => s.pass) ? 0 : 1;
