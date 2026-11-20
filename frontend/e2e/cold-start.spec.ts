import { expect, test } from "@playwright/test";

import { loginInBrowser } from "./support";

test("로그인 확인 응답을 기다리지 않고 홈에 데모 체험·로그인 입구를 바로 보여준다", async ({ page }) => {
  await page.route("**/api/auth/me", () => {
    // 응답하지 않는다: 깨는 중인 백엔드
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "데모 체험하기" })).toBeVisible({ timeout: 2000 });
  await expect(page.getByRole("link", { name: "계정으로 로그인" })).toBeVisible();
});

test("로그인 상태는 확인 응답이 오면 반영해 역할별 첫 화면으로 보낸다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  await page.goto("/");
  await expect(page).toHaveURL(/\/sessions$/);
});
