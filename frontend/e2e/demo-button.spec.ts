import { expect, test, type Page, type Route } from "@playwright/test";

import { appAlert } from "./support";

const unavailable = (route: Route, retryAfter: string) =>
  route.fulfill({
    status: 503,
    contentType: "application/json",
    headers: { "retry-after": retryAfter },
    body: JSON.stringify({ code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }),
  });

function demoButton(page: Page) {
  return page.getByRole("button", { name: /데모 체험하기|체험 준비 중|다시 시도하기/ });
}

test("누르면 '체험 준비 중…' 으로 바뀌고, 포커스를 유지한 채 중복 요청을 막고, 재시도 끝에 체험 화면으로 간다", async ({
  page,
}) => {
  let calls = 0;
  await page.route("**/api/demo/start", async (route) => {
    calls += 1;
    if (calls === 1) await unavailable(route, "3");
    else await route.continue();
  });
  await page.goto("/");
  const button = demoButton(page);
  await button.focus();
  await page.keyboard.press("Enter");

  await expect(button).toHaveText("체험 준비 중…");
  await expect(button).toHaveAttribute("aria-busy", "true");
  await expect(button).toHaveAttribute("aria-disabled", "true");
  await expect(button).toBeFocused();
  await expect(page.getByRole("status").filter({ hasText: "서버를 켜는 중" })).toBeVisible();
  // 진행 중 모양은 별도 스타일이고, 모바일 탭 강조(눌린 모양)는 남기지 않는다
  await expect(button).toHaveClass(/loading/);
  await expect(button).toHaveCSS("-webkit-tap-highlight-color", "rgba(0, 0, 0, 0)");

  // 재시도 대기 중 다시 눌러도(키보드·마우스) 새 요청은 나가지 않는다
  await page.keyboard.press("Enter");
  await page.keyboard.press("Space");
  await button.click({ force: true }); // aria-disabled 라 Playwright 는 기본으로 누르지 않는다
  expect(calls).toBe(1);

  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  expect(calls).toBe(2);

  // 뒤로 가면 버튼은 다시 쓸 수 있는 처음 상태다
  await page.goBack();
  await expect(demoButton(page)).toHaveText("데모 체험하기");
  await expect(demoButton(page)).not.toHaveAttribute("aria-busy", "true");
});

test("재시도 상한을 넘기면 버튼을 되살리고 오류와 함께 수동 재시도를 제공한다", async ({ page }) => {
  await page.addInitScript(() => {
    (globalThis as { __daumjariWakeMaxWaitMs?: number }).__daumjariWakeMaxWaitMs = 2000;
  });
  await page.route("**/api/demo/start", (route) => unavailable(route, "1"));
  await page.goto("/");
  const button = demoButton(page);
  await button.click();
  await expect(button).toHaveText("체험 준비 중…");

  await expect(appAlert(page)).toContainText("서버에 연결하지 못했어요");
  await expect(button).toHaveText("다시 시도하기");
  await expect(button).not.toHaveAttribute("aria-busy", "true");
  await expect(button).not.toHaveAttribute("aria-disabled", "true");

  await page.unroute("**/api/demo/start");
  await button.click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
});

test("복구할 수 없는 오류(429)는 기다릴 시간을 안내하고 버튼을 바로 되살린다", async ({ page }) => {
  await page.route("**/api/demo/start", (route) =>
    route.fulfill({
      status: 429,
      contentType: "application/json",
      headers: { "retry-after": "120" },
      body: JSON.stringify({ code: "TOO_MANY_ATTEMPTS", message: "too many" }),
    }),
  );
  await page.goto("/");
  const button = demoButton(page);
  await button.click();
  await expect(appAlert(page)).toContainText("2분 후 다시 시도해 주세요");
  await expect(button).toHaveText("다시 시도하기");
  await expect(button).not.toHaveAttribute("aria-busy", "true");
});
