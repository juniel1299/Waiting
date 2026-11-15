import { defineConfig, devices } from "@playwright/test";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
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
      command: "./gradlew bootRun --console=plain",
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
