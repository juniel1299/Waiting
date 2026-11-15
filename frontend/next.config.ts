import type { NextConfig } from "next";

// 브라우저는 같은 출처의 /api 만 부르고, Next 서버가 Spring Boot 로 넘긴다 (세션·CSRF 쿠키가 같은 출처에 남도록).
// next build·start·dev 시점의 BACKEND_URL 을 쓴다.
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  /* config options here */
  cacheComponents: true,
  partialPrefetching: true,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

export default nextConfig;
