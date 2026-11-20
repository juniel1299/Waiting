import type { NextRequest } from "next/server";

// 브라우저는 같은 출처의 /api 만 부르고, 여기서 Spring Boot 로 넘긴다 (Vercel·로컬 server.mjs 공통).
const BACKEND_URL =
  process.env.BACKEND_URL ??
  (process.env.BACKEND_HOSTPORT ? `http://${process.env.BACKEND_HOSTPORT}` : "http://localhost:8080");
const PROXY_TOKEN = process.env.PROXY_SHARED_SECRET ?? "";
// 무료 백엔드는 잠들었다 깨는 데 약 1분이 걸린다
const UPSTREAM_TIMEOUT_MS = 90_000;
export const maxDuration = 90;
// Vercel Function 을 백엔드(Render)·DB(Neon)와 같은 Singapore 에서 실행
export const preferredRegion = "sin1";

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
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return Response.json(
      { code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." },
      { status: 503 },
    );
  }

  const responseHeaders = new Headers();
  upstream.headers.forEach((value, name) => {
    if (!DROPPED_RESPONSE_HEADERS.has(name)) responseHeaders.set(name, value);
  });
  for (const cookie of upstream.headers.getSetCookie()) {
    responseHeaders.append("set-cookie", cookie);
  }
  const body = upstream.status === 204 ? null : await upstream.arrayBuffer();
  return new Response(body, { status: upstream.status, headers: responseHeaders });
}

export { proxy as DELETE, proxy as GET, proxy as PATCH, proxy as POST, proxy as PUT };
