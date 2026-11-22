import { expect, test, type Locator, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 4단계 공통 디자인·상호작용: 버튼 상태, 카드 클릭 영역, 메뉴 현재 위치, 입력 포커스, 모션 시간과 동작 줄이기 설정.
const style = (l: Locator, prop: string) => l.evaluate((el, p) => getComputedStyle(el).getPropertyValue(p), prop);
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function ownSession(title: string) {
  const bob = await ApiActor.login("bob");
  const res = await bob.post("/api/sessions", {
    title,
    startsAt: new Date(Date.now() + 48 * 3600 * 1000).toISOString(),
    location: "카드 장소",
    capacity: 2,
  });
  expect(res.status()).toBe(201);
  const id = (await res.json()).id as number;
  await bob.dispose();
  return id;
}

test("버튼은 기본·hover·키보드 포커스·비활성 상태가 서로 다르게 보이고, 전환은 150~200ms 다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  const search = page.getByRole("button", { name: "검색", exact: true }).filter({ visible: true });
  const reset = page.getByRole("button", { name: "초기화", exact: true }).filter({ visible: true });

  expect(await style(search, "cursor")).toBe("pointer");
  const idle = await style(search, "background-color");
  await search.hover();
  await expect.poll(() => style(search, "background-color")).not.toBe(idle);
  const duration = parseFloat(await style(search, "transition-duration"));
  expect(duration).toBeGreaterThanOrEqual(0.15);
  expect(duration).toBeLessThanOrEqual(0.2);

  // 키보드로 옮긴 포커스는 링이 보인다: 정렬 칸에서 Tab → 검색 버튼
  await visible(page, "정렬").focus();
  await page.keyboard.press("Tab");
  await expect(search).toBeFocused();
  expect(await style(search, "outline-style")).not.toBe("none");

  expect(await reset.isDisabled()).toBe(true);
  expect(await style(reset, "cursor")).toBe("not-allowed");
  expect(Number(await style(reset, "opacity"))).toBeLessThan(1);
});

test("검색 입력과 정렬 선택은 포커스되면 테두리·링이 바뀐다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  for (const name of ["검색어", "정렬"]) {
    const field = visible(page, name);
    const before = await style(field, "box-shadow");
    await field.focus();
    await expect.poll(() => style(field, "box-shadow")).not.toBe(before);
  }
});

test("메뉴는 현재 위치를 aria-current 로 표시하고 다른 항목과 다르게 보인다", async ({ page }) => {
  await loginInBrowser(page, "bob");
  const nav = page.getByRole("navigation", { name: "주 메뉴" });
  await expect(nav.getByRole("link", { name: "회차 목록" })).toHaveAttribute("aria-current", "page");
  await expect(nav.getByRole("link", { name: "내 신청" })).not.toHaveAttribute("aria-current", "page");
  await nav.getByRole("link", { name: "내가 만든 회차" }).click();
  await expect(nav.getByRole("link", { name: "내가 만든 회차" })).toHaveAttribute("aria-current", "page");
  await expect(nav.getByRole("link", { name: "회차 목록" })).not.toHaveAttribute("aria-current", "page");
  const current = await style(nav.getByRole("link", { name: "내가 만든 회차" }), "font-weight");
  const other = await style(nav.getByRole("link", { name: "회차 목록" }), "font-weight");
  expect(Number(current)).toBeGreaterThan(Number(other));
});

test("카드는 어디를 눌러도 상세로 가지만, 카드 안 관리 링크는 상세로 가지 않는다", async ({ page }) => {
  const title = uniqueTitle("카드 영역");
  const id = await ownSession(title);
  await loginInBrowser(page, "bob");
  await page.goto(`/me/sessions?q=${encodeURIComponent(title)}`);
  const card = page.locator(".cards .card:visible").filter({ hasText: title });

  await card.getByRole("link", { name: `${title} 수정` }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}/edit$`));
  await page.goBack();
  await card.getByRole("link", { name: `${title} 관리` }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}/manage$`));
  await page.goBack();
  // 제목이 아닌 곳(장소 글자)을 눌러도 상세로: 그 자리는 제목 링크의 덮개가 받는다 (force 는 덮개 확인만 건너뛰고 같은 좌표를 누른다)
  await card.getByText("카드 장소").click({ force: true });
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}$`));
});

test.describe("모션", () => {
  test("기본 설정에서는 카드 hover 에 작은 이동이 있다", async ({ page }) => {
    const title = uniqueTitle("모션");
    await ownSession(title);
    await loginInBrowser(page, "bob");
    await page.goto(`/sessions?q=${encodeURIComponent(title)}`);
    const card = page.locator(".cards .card:visible").first();
    await card.hover();
    await expect.poll(() => style(card, "transform")).not.toBe("none");
  });

  test("동작 줄이기 설정에서는 카드 이동이 없고 전환이 거의 즉시다", async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: "reduce" });
    const page = await context.newPage();
    const title = uniqueTitle("모션 줄이기");
    await ownSession(title);
    await loginInBrowser(page, "bob");
    await page.goto(`/sessions?q=${encodeURIComponent(title)}`);
    const card = page.locator(".cards .card:visible").first();
    await card.hover();
    await page.waitForTimeout(300);
    expect(await style(card, "transform")).toBe("none");
    expect(parseFloat(await style(card, "transition-duration"))).toBeLessThan(0.02);
    await context.close();
  });
});

test("키보드만으로 로그인 → 검색 → 카드에서 상세 → 예약 → 취소를 한다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const title = uniqueTitle("키보드 흐름");
  await operator.createSession(title, 3);
  await operator.dispose();

  await page.goto("/login");
  await page.getByLabel("아이디").focus();
  await page.keyboard.type("alice");
  await page.keyboard.press("Tab");
  await page.keyboard.type("daumjari-local-1!");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/sessions$/);

  await visible(page, "검색어").focus();
  await page.keyboard.type(title);
  await page.keyboard.press("Enter");
  const link = page.locator(".cards .card:visible").getByRole("link", { name: title });
  await link.focus();
  await expect(page.locator(".cards .card:visible").filter({ hasText: title })).toHaveCSS("outline-style", "solid");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: title })).toBeVisible();

  const reserve = page.getByRole("button", { name: "예약하기" });
  await reserve.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await page.getByRole("button", { name: "예약 취소" }).focus();
  await page.keyboard.press("Space");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");
});
