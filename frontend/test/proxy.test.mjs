// /api 프록시 Route Handler 단위 검증. 실행: npm test (Node 의 TS 타입 제거로 route.ts 를 바로 불러온다)
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, test } from "node:test";

const TIMEOUT_MS = 300;
let backend;
let proxy;

before(async () => {
  backend = createServer((req, res) => {
    if (req.url === "/api/slow") {
      setTimeout(() => res.end("{}"), TIMEOUT_MS * 3); // 잠든 백엔드처럼 오래 걸린다
      return;
    }
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ path: req.url }));
  });
  await new Promise((resolve) => backend.listen(0, resolve));
  process.env.BACKEND_URL = `http://localhost:${backend.address().port}`;
  process.env.PROXY_UPSTREAM_TIMEOUT_MS = String(TIMEOUT_MS);
  proxy = await import("../src/app/api/[...path]/route.ts");
});

after(() => backend.closeAllConnections() ?? backend.close());

function call(path, method = "GET") {
  const request = { method, headers: new Headers(), nextUrl: { search: "" }, arrayBuffer: async () => new ArrayBuffer(0) };
  return proxy[method](request, { params: Promise.resolve({ path }) });
}

test("백엔드 응답을 그대로 넘긴다", async () => {
  const res = await call(["sessions"]);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { path: "/api/sessions" });
});

test("업스트림이 제한 시간을 넘기면 플랫폼 504 대신 503 + Retry-After 로 답한다", async () => {
  const started = Date.now();
  const res = await call(["slow"]);
  assert.ok(Date.now() - started < TIMEOUT_MS * 2, "제한 시간 안에 끊어야 한다");
  assert.equal(res.status, 503);
  assert.ok(Number(res.headers.get("retry-after")) > 0);
  assert.equal((await res.json()).code, "BACKEND_UNAVAILABLE");
});

