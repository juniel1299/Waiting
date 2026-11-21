import { expect, test } from "@playwright/test";

import { appAlert, loginInBrowser, SEED_PASSWORD } from "./support";

test("로그인하면 세션·CSRF 쿠키가 같은 출처에 생기고, 로그아웃하면 보호 화면에서 로그인으로 돌아간다", async ({
  page,
  context,
}) => {
  await loginInBrowser(page, "alice");

  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.getByText("앨리스 님")).toBeVisible();
  const names = (await context.cookies()).map((c) => c.name);
  expect(names).toContain("JSESSIONID");

  // 로그아웃은 POST 라 CSRF 헤더가 맞아야 204 (틀리면 403 CSRF_INVALID)
  const logout = page.waitForResponse((r) => r.url().endsWith("/api/auth/logout"));
  await page.getByRole("button", { name: "로그아웃" }).click();
  const logoutResponse = await logout;
  expect(logoutResponse.status()).toBe(204);
  expect(logoutResponse.request().headers()["x-xsrf-token"]).toBeTruthy();
  await expect(page).toHaveURL(/\/login$/);

  await page.goto("/sessions");
  await expect(page).toHaveURL(/\/login$/);
});

test("비밀번호가 틀리면 오류를 안내하고 로그인하지 않는다", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("아이디").fill("alice");
  await page.getByLabel("비밀번호").fill("wrong-password");
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(appAlert(page)).toContainText("아이디 또는 비밀번호가 올바르지 않습니다");
  await expect(page).toHaveURL(/\/login$/);
});

test("운영자는 로그인 후 운영자 화면으로 이동한다", async ({ page }) => {
  await loginInBrowser(page, "operator");

  await expect(page).toHaveURL(/\/operator$/);
  await expect(page.getByRole("heading", { name: "회차 관리" })).toBeVisible();
});

test("로그아웃한 뒤 같은 화면에서 다시 로그인할 수 있다 (이전 로그인의 진행 상태가 남지 않는다)", async ({ page }) => {
  await loginInBrowser(page, "alice");
  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/login$/);
  const login = page.getByRole("button", { name: "로그인" });
  await expect(login).toBeEnabled();
  await page.getByRole("textbox", { name: "아이디" }).fill("alice");
  await page.getByRole("textbox", { name: "비밀번호" }).fill(SEED_PASSWORD);
  await login.click();
  await expect(page).toHaveURL(/\/sessions$/);
});
