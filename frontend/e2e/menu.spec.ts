import { expect, request, test, type Page } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser } from "./support";

// 확장 1단계: 계층형 메뉴(데스크톱 사이드바·모바일 서랍), 역할별 메뉴 노출, 직접 URL·API 접근의 서버 검사,
// 서비스 권한 관리(부여·회수·기록), 서랍 개폐 뒤 초점 복귀.
const sideMenu = (page: Page) => page.getByRole("complementary", { name: "사이드 메뉴" }).getByRole("navigation", { name: "주 메뉴" });
const baseURL = `http://localhost:${process.env.E2E_PORT ?? 3000}`;

async function apiStatus(page: Page, path: string) {
  const ctx = await request.newContext({ baseURL, storageState: await page.context().storageState() });
  const status = (await ctx.get(path)).status();
  await ctx.dispose();
  return status;
}

test("회원: 현재 묶음이 펼쳐지고 항목이 강조되며, 관리 메뉴는 없고 운영자 주소·API 는 막힌다", async ({ page }) => {
  await loginInBrowser(page, "bob");
  await page.goto("/clubs/promo");
  const club = sideMenu(page).getByRole("button", { name: "모임" });
  await expect(club).toHaveAttribute("aria-expanded", "true");
  await expect(sideMenu(page).getByRole("link", { name: "모임 홍보" })).toHaveAttribute("aria-current", "page");
  await expect(sideMenu(page).getByRole("link", { name: "모임 목록" })).not.toHaveAttribute("aria-current", "page");
  await expect(sideMenu(page).getByRole("button", { name: "관리" })).toHaveCount(0);
  // 다른 묶음은 접혀 있다가 열 수 있다
  const board = sideMenu(page).getByRole("button", { name: "게시판" });
  await expect(board).toHaveAttribute("aria-expanded", "false");
  await board.click();
  await sideMenu(page).getByRole("link", { name: "회원게시판" }).click();
  await expect(page).toHaveURL(/\/board$/);

  await page.goto("/operator/roles");
  await expect(appAlert(page).filter({ hasText: "운영자만 볼 수 있어요." })).toBeVisible();
  expect(await apiStatus(page, "/api/operator/roles")).toBe(403);
  expect(await apiStatus(page, "/api/operator/accounts")).toBe(403);
});

test("운영자가 개발자 역할을 주고 회수하면 기록이 남고, 개발자에게도 관리 메뉴·운영 API 는 없다", async ({ page, browser }) => {
  const carol = await ApiActor.login("carol");
  const carolId = (await (await carol.get("/api/auth/me")).json()).id;
  await carol.dispose();
  await loginInBrowser(page, "operator");
  await page.goto("/operator/roles");
  await expect(page.getByRole("heading", { name: "운영자 · 서비스 권한 관리" })).toBeVisible();
  await expect(page.getByRole("table").first()).toContainText("서비스 권한 관리 (자기 자신 제외)");
  await expect(sideMenu(page).getByRole("link", { name: "서비스 권한 관리" })).toHaveAttribute("aria-current", "page");

  await page.getByLabel("아이디·닉네임").fill("carol");
  await page.getByRole("button", { name: "검색" }).click();
  const row = page.getByTestId("role-row").filter({ hasText: "carol" });
  page.once("dialog", (d) => d.accept("에러 로그 담당 (e2e)"));
  await row.getByRole("button", { name: "개발자 부여" }).click();
  await expect(row.getByRole("button", { name: "개발자 회수" })).toBeVisible();
  await expect(page.getByTestId("role-change").first()).toContainText("에러 로그 담당 (e2e)");

  const devContext = await browser.newContext();
  const dev = await devContext.newPage();
  await loginInBrowser(dev, "carol");
  expect((await (await dev.request.get("/api/auth/me")).json()).roles).toEqual(["DEVELOPER"]);
  await expect(sideMenu(dev).getByRole("button", { name: "관리" })).toHaveCount(0);
  expect(await apiStatus(dev, "/api/operator/accounts")).toBe(403);
  expect(await apiStatus(dev, "/api/operator/usage")).toBe(403);
  await devContext.close();

  page.once("dialog", (d) => d.accept("e2e 정리"));
  await row.getByRole("button", { name: "개발자 회수" }).click();
  await expect(row.getByRole("button", { name: "개발자 부여" })).toBeVisible();
  expect(carolId).toBeGreaterThan(0);
});

test.describe("모바일 서랍", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test("메뉴를 열면 첫 항목에 초점, Esc 로 닫으면 메뉴 버튼으로 초점이 돌아오고, 항목을 고르면 새 화면으로 가며 가로 넘침이 없다", async ({ page }) => {
    await loginInBrowser(page, "bob");
    await page.goto("/board");
    const menuButton = page.getByRole("button", { name: "메뉴", exact: true });
    await expect(page.getByRole("complementary", { name: "사이드 메뉴" })).toBeHidden();
    await menuButton.click();
    const drawer = page.getByRole("dialog", { name: "메뉴" });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole("button", { name: "닫기" })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await expect(menuButton).toBeFocused();

    await menuButton.click();
    await expect(drawer.getByRole("link", { name: "회원게시판" })).toHaveAttribute("aria-current", "page");
    await drawer.getByRole("button", { name: "모임" }).click();
    await drawer.getByRole("link", { name: "모임 목록" }).click();
    await expect(page).toHaveURL(/\/clubs$/);
    await expect(drawer).toHaveCount(0);
    await expect(page.getByRole("heading", { level: 1, name: "모임 목록" })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});

test("데스크톱 사이드바를 접고 펼치면 상태가 유지된다", async ({ page }) => {
  await loginInBrowser(page, "bob");
  await page.goto("/sessions");
  const toggle = page.getByRole("button", { name: "메뉴 접기" });
  await toggle.click();
  await expect(page.getByRole("button", { name: "메뉴 펼치기" })).toHaveAttribute("aria-expanded", "false");
  await page.reload();
  await expect(page.getByRole("button", { name: "메뉴 펼치기" })).toBeVisible();
  await page.getByRole("button", { name: "메뉴 펼치기" }).click();
  await expect(sideMenu(page).getByRole("link", { name: "회차 목록" })).toHaveAttribute("aria-current", "page");
});
