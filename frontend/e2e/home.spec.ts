import { expect, test, type Page, type Route } from "@playwright/test";

import { appAlert } from "./support";

const VIEWPORTS = [
  { name: "데스크톱", width: 1280, height: 800 },
  { name: "모바일", width: 375, height: 812 },
];

const unavailable = (route: Route, retryAfter = "3") =>
  route.fulfill({
    status: 503,
    contentType: "application/json",
    headers: { "retry-after": retryAfter },
    body: JSON.stringify({ code: "BACKEND_UNAVAILABLE", message: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }),
  });

const top = async (page: Page, locator: ReturnType<Page["locator"]>) => (await locator.boundingBox())!.y;

for (const vp of VIEWPORTS) {
  test.describe(vp.name, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } });

    test("소개 → 3단계 흐름 → 데모 버튼·첫 접속 안내 → 시연 보기 순서이고, 로그인·회원가입은 한 줄", async ({ page }) => {
      await page.goto("/");
      const intro = page.getByRole("heading", { level: 1 });
      const flow = page.getByRole("list", { name: "체험 흐름" });
      const button = page.getByRole("button", { name: "데모 체험하기" });
      const hint = page.getByText(/무료 서버라 .*첫 시작.*걸릴 수 있어요/);
      const tour = page.getByRole("link", { name: /시연 보기/ });
      const ys = [await top(page, intro), await top(page, flow), await top(page, button), await top(page, hint), await top(page, tour)];
      expect(ys, "세로 순서").toEqual([...ys].sort((a, b) => a - b));

      const login = page.getByRole("link", { name: "로그인" });
      const signup = page.getByRole("link", { name: "회원가입" });
      expect(Math.abs((await top(page, login)) - (await top(page, signup))), "같은 줄").toBeLessThan(4);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    });

    test("3단계 카드 제목이 중간에 끊기지 않는다 (넓으면 가로, 좁으면 세로)", async ({ page }) => {
      await page.goto("/");
      const titles = page.getByRole("list", { name: "체험 흐름" }).locator("li strong");
      await expect(titles).toHaveCount(3);
      for (const t of await titles.all()) {
        expect(await t.evaluate((el) => {
        // 요소가 flex 항목이면 getClientRects 는 늘 1개라, 글자 줄 상자의 서로 다른 top 수로 줄 수를 센다
        const range = document.createRange();
        range.selectNodeContents(el);
        return new Set([...range.getClientRects()].map((r) => Math.round(r.top))).size;
      }), await t.textContent() ?? "").toBe(1);
      }
      const boxes = await Promise.all((await page.getByRole("list", { name: "체험 흐름" }).locator("li").all()).map((li) => li.boundingBox()));
      const sameRow = boxes.every((b) => Math.abs(b!.y - boxes[0]!.y) < 2);
      expect(sameRow, "넓으면 한 줄 가로, 좁으면 세로").toBe(vp.width >= 600);
    });

    test("서버를 깨우는 안내는 맨 위가 아니라 데모 버튼 바로 아래 상태 영역에 나온다", async ({ page }) => {
      let calls = 0;
      await page.route("**/api/demo/start", async (route) => (++calls === 1 ? unavailable(route) : route.continue()));
      await page.goto("/");
      const button = page.getByRole("button", { name: /데모 체험하기|체험 준비 중/ });
      await button.click();
      const status = page.getByRole("status").filter({ hasText: "서버를 깨우는 중이에요" });
      await expect(status).toHaveCount(1);
      expect(await top(page, status)).toBeGreaterThan(await top(page, button));
      expect(await top(page, status)).toBeLessThan(await top(page, page.getByRole("link", { name: /1분 시연 보기/ })));
      await expect(status.getByRole("link", { name: "기다리는 동안 시연 보기" })).toHaveAttribute("target", "_blank");
      await expect(page).toHaveURL(/\/sessions\/\d+$/);
    });
  });
}

test("시간 초과 뒤 오류와 다시 시도도 같은 영역에 나오고, 키보드 포커스가 버튼에 남는다", async ({ page }) => {
  await page.addInitScript(() => {
    (globalThis as { __daumjariWakeMaxWaitMs?: number }).__daumjariWakeMaxWaitMs = 2000;
  });
  await page.route("**/api/demo/start", (route) => unavailable(route, "1"));
  await page.goto("/");
  const button = page.getByRole("button", { name: /데모 체험하기|체험 준비 중|다시 시도하기/ });
  await button.focus();
  await page.keyboard.press("Enter");
  await expect(button).toHaveAttribute("aria-busy", "true");
  await expect(button).toHaveText("다시 시도하기");
  await expect(button).toBeFocused();
  const alert = appAlert(page);
  await expect(alert).toContainText("서버에 연결하지 못했어요");
  expect(await top(page, alert)).toBeGreaterThan(await top(page, button));
  expect(await top(page, alert)).toBeLessThan(await top(page, page.getByRole("link", { name: /1분 시연 보기/ })));
  await expect(page.getByRole("status").filter({ hasText: "서버를 깨우는 중이에요" })).toHaveCount(0);

  await page.unroute("**/api/demo/start");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
});
