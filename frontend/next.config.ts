import type { NextConfig } from "next";

// /api 는 src/app/api/[...path]/route.ts 가 백엔드로 넘긴다 (BACKEND_URL 은 실행 시점에 읽음).

const nextConfig: NextConfig = {
  // 같은 폴더에서 개발 서버를 하나 더 띄울 때 빌드 폴더를 나눈다 (기본 .next)
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  /* config options here */
  cacheComponents: true,
  partialPrefetching: true,
};

export default nextConfig;
