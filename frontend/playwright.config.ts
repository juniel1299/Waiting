import { defineConfig, devices } from "@playwright/test";

// 이미 개발 서버를 띄워 둔 경우엔 E2E_PORT·E2E_BACKEND_PORT 로 다른 포트를 써서 새 서버로 검증한다.
const BACKEND_PORT = Number(process.env.E2E_BACKEND_PORT ?? 8080);
const BACKEND_URL = `http://localhost:${BACKEND_PORT}`;
const FRONTEND_PORT = Number(process.env.E2E_PORT ?? 3000);

/**
 * 실제 Spring Boot(local 프로필, docker compose DB) + Next 프로덕션 빌드를 함께 띄워 검증한다.
 * 사전 조건: 루트에서 `docker compose up -d`.
 */
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
    trace: "off",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      // 로그인 제한 창을 짧게: 제한 후 정상 로그인까지 e2e 에서 기다릴 수 있게.
      // 채팅 속도 제한은 넉넉히: 이전 기록 e2e 가 120건을 빨리 넣는다(속도 제한 자체는 백엔드 테스트가 확인)
      command: `./gradlew bootRun --console=plain --args='--server.port=${BACKEND_PORT} --daumjari.login-throttle.window=${process.env.E2E_THROTTLE_WINDOW_SECONDS ?? 10}s --daumjari.chat.max-per-window=1000'`,
      cwd: "../backend",
      url: `${BACKEND_URL}/actuator/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
    {
      command: `npm run build && npm run start -- -p ${FRONTEND_PORT}`,
      url: `http://localhost:${FRONTEND_PORT}`,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
      env: { BACKEND_URL },
    },
  ],
});
