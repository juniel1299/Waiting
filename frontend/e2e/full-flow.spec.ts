import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser } from "./support";

// 4단계 전체 흐름: 가입 → 회차 만들기 → 검색 → 상세에서 대기 신청 → 앞사람 취소로 순번 변경(강조·안내) → 확정(강조·안내)
// → 운영자 화면. 데스크톱·모바일 뷰포트에서 같은 흐름을 키보드·터치 없이 실제 버튼으로 진행한다.
const PASSWORD = "full-flow-pass-1!";
const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function flow(page: Page) {
  const loginId = `ff_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  const title = `전체 흐름 ${loginId}`;

  // 가입 → 로그인
  await page.goto("/signup");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호", { exact: true }).fill(PASSWORD);
  await page.getByLabel("비밀번호 확인").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await expect(page).toHaveURL(/\/login/);
  await loginInBrowser(page, loginId, PASSWORD);

  // 회차 만들기 (정원 1)
  await page.getByRole("link", { name: "회차 만들기" }).first().click();
  await page.getByLabel("제목").fill(title);
  await page.getByLabel("시작 일시 (서울)").fill("2031-05-02T19:30");
  await page.getByLabel("장소").fill("전체 흐름룸");
  await page.getByLabel("정원").fill("1");
  await page.getByRole("button", { name: "회차 만들기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  const sessionId = Number(new URL(page.url()).pathname.split("/").pop());

  // 다른 회원: bob 확정, carol 대기 1번 → 이 사용자는 대기 2번이 된다
  const bob = await ApiActor.login("bob");
  const carol = await ApiActor.login("carol");
  const held = await (await bob.reserve(sessionId)).json();
  const carolWait = await (await carol.waitlist(sessionId)).json();

  // 검색으로 찾아 상세로
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "회차 목록" }).click();
  await visible(page, "검색어").fill(title);
  await visible(page, "검색어").press("Enter");
  const card = page.locator(".cards .card:visible").filter({ hasText: title });
  await expect(card).toContainText("1 / 정원 1");
  await card.getByRole("link", { name: title }).click();
  await page.getByRole("button", { name: "대기 신청" }).click();
  const mine = page.getByTestId("my-reservation");
  await expect(mine).toHaveText("대기 2번");

  // 앞사람(bob) 취소 → carol 확정, 이 사용자는 대기 1번: 강조와 텍스트 안내
  expect((await bob.cancel(held.id)).status()).toBe(200);
  await expect(mine).toHaveText("대기 1번", { timeout: POLL_MS + 5000 });
  await expect(page.getByRole("status").filter({ hasText: "대기 순번이 2번에서 1번으로 앞당겨졌어요." })).toBeVisible();
  await expect(mine).toHaveAttribute("data-changed", "true");

  // carol 취소 → 이 사용자 확정
  expect((await carol.cancel(carolWait.id)).status()).toBe(200);
  await expect(mine).toHaveText("예약 확정", { timeout: POLL_MS + 5000 });
  await expect(page.getByRole("status").filter({ hasText: "대기 순번이 되어 예약이 확정됐어요." })).toBeVisible();
  await expect(mine).toHaveAttribute("data-changed", "true");
  // 강조는 잠깐만
  await expect(mine).not.toHaveAttribute("data-changed", "true", { timeout: 3000 });
  await Promise.all([bob.dispose(), carol.dispose()]);

  // 운영자 화면: 이 회원과 회차가 보인다
  const op = await page.context().browser()!.newContext({ viewport: page.viewportSize()! });
  const opPage = await op.newPage();
  await loginInBrowser(opPage, "operator");
  await expect(opPage.getByRole("heading", { name: "운영자 · 사용량" })).toBeVisible();
  await opPage.getByRole("navigation", { name: "운영자 메뉴" }).getByRole("link", { name: "계정 관리" }).click();
  await visible(opPage, "아이디·이름").fill(loginId);
  await opPage.getByRole("button", { name: "계정 검색" }).click();
  await expect(opPage.locator(".cards .card:visible").filter({ hasText: loginId })).toContainText("신청 1건 · 만든 회차 1개");
  const overflow = await opPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await op.close();
}

test("데스크톱: 가입부터 순번 변경 강조, 운영자 화면까지", async ({ page }) => {
  test.setTimeout(90_000);
  await flow(page);
});

test.describe("모바일", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test("모바일: 가입부터 순번 변경 강조, 운영자 화면까지", async ({ page }) => {
    test.setTimeout(90_000);
    await flow(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
