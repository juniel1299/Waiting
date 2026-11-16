// Next 를 감싸는 얇은 서버. Next 의 /api rewrite 프록시는 X-Forwarded-For 를 붙이지 않고,
// 클라이언트가 보낸 값을 그대로 백엔드로 넘긴다 (README "클라이언트 IP 판별" 실측).
// 그래서 여기서 클라이언트가 보낸 전달 헤더를 버리고 실제 접속 주소로 다시 쓴다.
import { createServer } from "node:http";
import next from "next";

const dev = process.argv.includes("--dev");
const portFlag = process.argv.indexOf("-p");
const port = Number(portFlag >= 0 ? process.argv[portFlag + 1] : (process.env.PORT ?? 3000));

// 이 서버 앞에 로드밸런서 등이 있을 때만 그 주소를 넣는다 (쉼표 구분). 비어 있으면 아무 전달 헤더도 믿지 않는다.
const trustedProxies = new Set(
  (process.env.FRONT_TRUSTED_PROXIES ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean),
);

/** 신뢰 프록시를 거쳐 왔으면 그 프록시들이 붙인 X-Forwarded-For 를 오른쪽부터 읽고, 아니면 실제 접속 주소. */
function clientIp(req) {
  const peer = req.socket.remoteAddress ?? "";
  if (!trustedProxies.has(peer)) {
    return peer;
  }
  const hops = String(req.headers["x-forwarded-for"] ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  for (let i = hops.length - 1; i >= 0; i--) {
    if (!trustedProxies.has(hops[i])) {
      return hops[i];
    }
  }
  return peer;
}

const app = next({ dev });
const handle = app.getRequestHandler();
await app.prepare();

createServer((req, res) => {
  req.headers["x-forwarded-for"] = clientIp(req);
  delete req.headers["forwarded"];
  delete req.headers["x-real-ip"];
  if (!trustedProxies.has(req.socket.remoteAddress ?? "")) {
    delete req.headers["x-forwarded-proto"];
    delete req.headers["x-forwarded-host"];
    delete req.headers["x-forwarded-port"];
  }
  handle(req, res);
}).listen(port, () => {
  console.log(`> 다음자리 프런트 http://localhost:${port} (${dev ? "development" : "production"})`);
});
