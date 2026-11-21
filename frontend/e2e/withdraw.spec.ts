import { expect, test } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle } from "./support";

const PASSWORD = "withdraw-pass-1!";

function newLoginId() {
  return `wd_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
}

test("계정 메뉴 → 탈퇴 안내·비밀번호 재확인 → 탈퇴하면 대기자가 승격되고 다른 기기 세션·재로그인이 막힌다", async ({
  page,
  browser,
}) => {
  const loginId = newLoginId();
  const member = await ApiActor.signup(loginId, PASSWORD);
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const sessionId = await operator.createSession(uniqueTitle("탈퇴 e2e"), 1);
  expect((await member.reserve(sessionId)).status()).toBe(201);
  const waiting = await (await bob.waitlist(sessionId)).json();
  expect(waiting.status).toBe("WAITING");

  // 다른 기기에서도 로그인해 둔다
  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await loginInBrowser(otherPage, loginId, PASSWORD);

  await loginInBrowser(page, loginId, PASSWORD);
  await page.getByRole("link", { name: "회원 탈퇴" }).click();
  await expect(page).toHaveURL(/\/account\/withdraw$/);
  await expect(page.getByText("시작 전 확정 예약 1건")).toBeVisible();
  await expect(page.getByText("대기 중인 다음 분이 자동으로 확정돼요")).toBeVisible();
  await expect(page.getByText("되돌릴 수 없어요")).toBeVisible();

  // 키보드만으로: 비밀번호 입력 후 Enter
  await page.getByRole("textbox", { name: "비밀번호" }).fill(PASSWORD);
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/login\?withdrawn=1$/);
  await expect(page.getByRole("status")).toContainText("탈퇴가 완료됐어요");

  const mine = await (await bob.get("/api/me/reservations")).json();
  expect(mine.find((r: { id: number }) => r.id === waiting.id).status).toBe("CONFIRMED");

  await otherPage.goto("/sessions");
  await expect(otherPage).toHaveURL(/\/login$/);

  await page.getByRole("textbox", { name: "아이디" }).fill(loginId);
  await page.getByRole("textbox", { name: "비밀번호" }).fill(PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(appAlert(page)).toContainText("아이디 또는 비밀번호가 올바르지 않습니다");

  await Promise.all([other.close(), member.dispose(), operator.dispose(), bob.dispose()]);
});

test("비밀번호가 틀리면 탈퇴하지 않고 안내한다", async ({ page }) => {
  const loginId = newLoginId();
  const member = await ApiActor.signup(loginId, PASSWORD);
  await loginInBrowser(page, loginId, PASSWORD);
  await page.goto("/account/withdraw");
  await page.getByRole("textbox", { name: "비밀번호" }).fill("wrong-password");
  await page.getByRole("button", { name: "탈퇴하기" }).click();
  await expect(appAlert(page)).toContainText("비밀번호가 올바르지 않아요");
  expect((await member.get("/api/auth/me")).status()).toBe(200);
  await member.dispose();
});

test("운영자에게는 탈퇴 메뉴가 없고, 주소로 들어가도 탈퇴할 수 없다고 안내한다", async ({ page }) => {
  await loginInBrowser(page, "operator");
  await expect(page.getByRole("link", { name: "회원 탈퇴" })).toHaveCount(0);
  await page.goto("/account/withdraw");
  await expect(page.getByText("운영자·체험 계정은 탈퇴할 수 없어요")).toBeVisible();
  await expect(page.getByRole("button", { name: "탈퇴하기" })).toHaveCount(0);
});
