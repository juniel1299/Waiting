import { expect, test, type Route } from "@playwright/test";

const unavailable = (route: Route) =>
  route.fulfill({
    status: 503,
    contentType: "application/json",
    headers: { "retry-after": "3" },
    body: JSON.stringify({ code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }),
  });

test("첫 화면에 서비스 목적과 '대기 신청 → 빈자리 발생 → 예약 확정' 흐름, 시연 링크가 보인다", async ({ page }) => {
  await page.goto("/");
  const flow = page.getByRole("list", { name: "체험 흐름" });
  await expect(flow.getByRole("listitem")).toHaveText([/대기 신청/, /빈자리 발생/, /예약 확정/]);
  await expect(page.getByRole("button", { name: "데모 체험하기" })).toBeVisible();
  await expect(page.getByRole("link", { name: /시연 보기/ })).toHaveAttribute("href", "/tour");
});

test("시연 페이지는 백엔드 없이 열리고 영상·스크린샷이 로드된다 (/api 요청 0회)", async ({ page }) => {
  let apiCalls = 0;
  await page.route("**/api/**", (route) => {
    apiCalls += 1;
    return route.abort();
  });
  await page.goto("/tour");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("시연");
  const video = page.locator("video");
  await expect(video).toHaveAttribute("poster", /\.(png|jpg|webp)$/);
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState)).toBeGreaterThanOrEqual(1);
  const images = page.locator("main img");
  expect(await images.count()).toBeGreaterThanOrEqual(3);
  for (const img of await images.all()) {
    await img.scrollIntoViewIfNeeded();
    await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth > 0)).toBe(true);
  }
  await expect(page.getByText("서버를 깨우는 중이에요")).toHaveCount(0);
  expect(apiCalls).toBe(0);
});

test("서버를 깨우는 동안 안내에 '기다리는 동안 시연 보기' 링크가 새 탭으로 열리게 있다", async ({ page }) => {
  let calls = 0;
  await page.route("**/api/demo/start", async (route) => (++calls === 1 ? unavailable(route) : route.continue()));
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  const tourLinks = page.getByRole("link", { name: "기다리는 동안 시연 보기" });
  await expect(tourLinks.first()).toBeVisible();
  for (const link of await tourLinks.all()) {
    await expect(link).toHaveAttribute("href", "/tour");
    await expect(link).toHaveAttribute("target", "_blank");
  }
  // 새 탭으로 열어도 원래 탭의 재시도는 이어져 체험 화면으로 간다
  const [tour] = await Promise.all([page.context().waitForEvent("page"), tourLinks.first().click()]);
  await expect(tour).toHaveURL(/\/tour$/);
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
});

test("모바일 세로 화면에서 첫 화면·시연 페이지에 가로 넘침이 없다", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  for (const path of ["/", "/tour"]) {
    await page.goto(path);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), path).toBe(true);
  }
});
