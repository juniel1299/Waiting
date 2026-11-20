import { expect, test, type Route } from "@playwright/test";

import { appAlert, loginInBrowser } from "./support";

// 잠든 무료 백엔드를 흉내 낸다: 앞의 몇 번은 플랫폼 504·프록시 503·연결 실패로 답하고, 그 뒤엔 실제 백엔드로 넘긴다.
const WAKING_NOTICE = "서버를 깨우는 중이에요";

function failFirst(times: number, fail: (route: Route) => Promise<void>) {
  let calls = 0;
  const handler = async (route: Route) => {
    calls += 1;
    if (calls <= times) await fail(route);
    else await route.continue();
  };
  return { handler, calls: () => calls };
}

const gatewayTimeout = (route: Route) =>
  route.fulfill({ status: 504, contentType: "text/plain", headers: { "retry-after": "1" }, body: "An error occurred" });

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

test("데모 시작이 504 면 깨우는 안내를 띄우고 자동으로 다시 시도해 체험 화면으로 들어간다", async ({ page }) => {
  const start = failFirst(2, gatewayTimeout);
  await page.route("**/api/demo/start", start.handler);

  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page.getByText(WAKING_NOTICE)).toBeVisible();

  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await expect(page.getByText(WAKING_NOTICE)).toBeHidden();
  expect(start.calls()).toBe(3);
});

test("조회가 연결 실패·502 면 다시 시도해 화면을 그린다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  let calls = 0;
  await page.route("**/api/auth/me", async (route) => {
    calls += 1;
    if (calls === 1) await route.abort("connectionrefused");
    else if (calls === 2) await route.fulfill({ status: 502, headers: { "retry-after": "1" }, body: "" });
    else await route.continue();
  });

  await page.goto("/sessions");
  await expect(page.getByText(WAKING_NOTICE)).toBeVisible();
  // 연결 실패엔 Retry-After 가 없어 기본 간격(5초)을 기다린다
  await expect(page.getByText("앨리스 님")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(WAKING_NOTICE)).toBeHidden();
  expect(calls).toBe(3);
});

test("멱등이 아닌 POST(대기 신청)는 503 이어도 다시 보내지 않고 오류를 보여준다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);

  let calls = 0;
  await page.route("**/api/sessions/*/waitlist", async (route) => {
    calls += 1;
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      headers: { "retry-after": "1" },
      body: JSON.stringify({ code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }),
    });
  });
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(appAlert(page)).toContainText("서버에 연결하지 못했어요");
  await page.waitForTimeout(2500);
  expect(calls).toBe(1);
});
