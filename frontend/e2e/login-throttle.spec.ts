import { expect, test, type Page } from "@playwright/test";

import { appAlert, SEED_PASSWORD } from "./support";

/** e2e 백엔드는 playwright.config.ts 에서 제한 창을 E2E_THROTTLE_WINDOW_SECONDS(10초)로 짧게 띄운다. */
const WINDOW_SECONDS = Number(process.env.E2E_THROTTLE_WINDOW_SECONDS ?? 10);
const MAX_PER_ACCOUNT_IP = 5;

test("실제 /api 프록시를 거쳐 실패가 쌓이면 남은 시간을 안내하고, 시간이 지나면 정상 로그인·로그아웃된다", async ({
  page,
}) => {
  test.setTimeout(60_000);
  await page.goto("/login");

  // 매 시도마다 다른 X-Forwarded-For 를 위조해도 프런트 서버가 실제 접속 주소로 덮어써 같은 키로 센다
  for (let i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
    await page.setExtraHTTPHeaders({ "X-Forwarded-For": `198.18.${i}.${i + 1}` });
    await tryLogin(page, "carol", "wrong-password");
    await expect(appAlert(page)).toContainText("아이디 또는 비밀번호가 올바르지 않습니다");
  }

  await page.setExtraHTTPHeaders({ "X-Forwarded-For": "198.18.99.99" });
  const throttled = page.waitForResponse((r) => r.url().endsWith("/api/auth/login"));
  await tryLogin(page, "carol", SEED_PASSWORD);
  const response = await throttled;
  expect(response.status()).toBe(429);
  const retryAfter = Number(response.headers()["retry-after"]);
  expect(retryAfter).toBeGreaterThan(0);
  expect(retryAfter).toBeLessThanOrEqual(WINDOW_SECONDS);
  await expect(appAlert(page)).toContainText(`로그인 시도가 너무 많아요. ${retryAfter}초 후 다시 시도해 주세요.`);
  await expect(page).toHaveURL(/\/login$/);

  await page.waitForTimeout(retryAfter * 1000 + 500);
  await page.setExtraHTTPHeaders({});
  await tryLogin(page, "carol", SEED_PASSWORD);
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.getByText("캐롤 님")).toBeVisible();

  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/login$/);
});

async function tryLogin(page: Page, loginId: string, password: string) {
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}
