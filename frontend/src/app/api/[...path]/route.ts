import type { NextRequest } from "next/server";

// 브라우저는 같은 출처의 /api 만 부르고, 여기서 Spring Boot 로 넘긴다 (Vercel·로컬 server.mjs 공통).
const BACKEND_URL =
  process.env.BACKEND_URL ??
  (process.env.BACKEND_HOSTPORT ? `http://${process.env.BACKEND_HOSTPORT}` : "http://localhost:8080");
const PROXY_TOKEN = process.env.PROXY_SHARED_SECRET ?? "";
// 무료 백엔드는 잠들었다 깨는 데 1~2분이 걸린다. 함수 제한(maxDuration)에 걸려 플랫폼 504 가 나기 전에
// 먼저 끊고 503 + Retry-After 로 답해, 브라우저가 "깨우는 중" 안내와 함께 다시 시도하게 한다.
export const maxDuration = 90;
const UPSTREAM_TIMEOUT_MS = Number(process.env.PROXY_UPSTREAM_TIMEOUT_MS ?? (maxDuration - 5) * 1000);
const RETRY_AFTER_SECONDS = "5";

const FORWARDED_REQUEST_HEADERS = ["accept", "accept-language", "content-type", "cookie", "x-xsrf-token"];
const DROPPED_RESPONSE_HEADERS = new Set([
  "connection",
  "content-encoding",
  "content-length",
  "set-cookie",
  "transfer-encoding",
]);

/**
 * 클라이언트 IP. Vercel 은 x-forwarded-for 를 자기가 본 주소로 덮어쓰고(위조 방지), 로컬은 server.mjs 가 덮어쓴다.
 * 그 밖의 전달 헤더는 백엔드로 넘기지 않는다.
 */
function clientIp(request: NextRequest): string | null {
  return request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || null;
}

async function proxy(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  const target = new URL(`/api/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`, BACKEND_URL);

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const ip = clientIp(request);
  if (ip) {
    headers.set("x-daumjari-client-ip", ip); // prod: 프록시 토큰과 함께만 쓰인다
    headers.set("x-forwarded-for", ip); // 로컬: 백엔드 Tomcat 이 loopback 프록시로 믿고 쓴다
  }
  if (PROXY_TOKEN) headers.set("x-daumjari-proxy-token", PROXY_TOKEN);

  let upstream: Response;
  let body: ArrayBuffer | null;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    body = upstream.status === 204 ? null : await upstream.arrayBuffer();
  } catch {
    return Response.json(
      { code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." },
      { status: 503, headers: { "retry-after": RETRY_AFTER_SECONDS } },
    );
  }

  const responseHeaders = new Headers();
  upstream.headers.forEach((value, name) => {
    if (!DROPPED_RESPONSE_HEADERS.has(name)) responseHeaders.set(name, value);
  });
  for (const cookie of upstream.headers.getSetCookie()) {
    responseHeaders.append("set-cookie", cookie);
  }
  return new Response(body, { status: upstream.status, headers: responseHeaders });
}

export { proxy as DELETE, proxy as GET, proxy as PATCH, proxy as POST, proxy as PUT };
