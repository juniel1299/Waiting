import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

const PASSWORD = "mine-pass-1!";
const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);

function newLoginId() {
  return `my_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
}

test("예약·대기 신청 뒤 메뉴의 '내 신청'에 상태와 함께 보이고, 다른 참가자 취소로 승격되면 새로고침 없이 확정으로 바뀐다", async ({
  page,
}) => {
  const loginId = newLoginId();
  const me = await ApiActor.signup(loginId, PASSWORD);
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const openTitle = uniqueTitle("내 신청 확정");
  const fullTitle = uniqueTitle("내 신청 대기");
  const openId = await operator.createSession(openTitle, 3);
  const fullId = await operator.createSession(fullTitle, 1);
  const held = await (await bob.reserve(fullId)).json();

  await loginInBrowser(page, loginId, PASSWORD);
  await page.goto(`/sessions/${openId}`);
  await page.getByRole("button", { name: "예약하기" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await page.goto(`/sessions/${fullId}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  await page.getByRole("link", { name: "내 신청" }).click();
  await expect(page).toHaveURL(/\/me\/reservations$/);
  const openItem = page.getByRole("listitem").filter({ hasText: openTitle });
  const fullItem = page.getByRole("listitem").filter({ hasText: fullTitle });
  await expect(openItem.getByTestId("reservation-status")).toHaveText("예약 확정");
  await expect(openItem).toContainText("E2E 스터디룸");
  await expect(fullItem.getByTestId("reservation-status")).toHaveText("대기 1번");

  // 다른 참가자(확정자)가 취소하면 내 대기가 승격된다: 화면은 다음 폴링에 바뀐다
  expect((await bob.cancel(held.id)).status()).toBe(200);
  await expect(fullItem.getByTestId("reservation-status")).toHaveText("예약 확정", { timeout: POLL_MS + 5000 });

  // 상세와 일치
  await fullItem.getByRole("link", { name: fullTitle }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${fullId}$`));
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");

  await Promise.all([me.dispose(), operator.dispose(), bob.dispose()]);
});

test("다른 참가자의 신청은 보이지 않고, 신청이 없으면 회차 목록으로 안내한다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const bobsTitle = uniqueTitle("남의 신청");
  const sid = await operator.createSession(bobsTitle, 3);
  expect((await bob.reserve(sid)).status()).toBe(201);
  const loginId = newLoginId();
  await ApiActor.signup(loginId, PASSWORD);

  await loginInBrowser(page, loginId, PASSWORD);
  await page.getByRole("link", { name: "내 신청" }).click();
  await expect(page.getByText("아직 신청한 회차가 없어요")).toBeVisible();
  // 숨겨 둔 이전 화면(회차 목록)은 빼고 보이는 항목만 센다
  await expect(page.getByRole("listitem").filter({ hasText: bobsTitle })).toHaveCount(0);
  await page.getByRole("link", { name: "회차 목록 보기" }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await Promise.all([operator.dispose(), bob.dispose()]);
});

test("탭이 다시 보이면 즉시 최신 상태를 조회한다", async ({ page }) => {
  const loginId = newLoginId();
  await ApiActor.signup(loginId, PASSWORD);
  await loginInBrowser(page, loginId, PASSWORD);
  await page.goto("/me/reservations");
  await expect(page.getByText("아직 신청한 회차가 없어요")).toBeVisible();
  const refreshed = page.waitForRequest((r) => r.url().endsWith("/api/me/reservations"));
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await refreshed;
});

test("데모 방문자도 메뉴에서 자기 체험 신청만 본다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");
  await page.getByRole("link", { name: "내 신청" }).click();
  const items = page.getByRole("listitem");
  await expect(items).toHaveCount(1);
  await expect(items.first()).toContainText("체험 회차");
  await expect(items.first().getByTestId("reservation-status")).toHaveText("대기 1번");
});

test("운영자 메뉴에는 '내 신청'이 없다", async ({ page }) => {
  await loginInBrowser(page, "operator");
  await expect(page.getByRole("link", { name: "회차 관리" })).toBeVisible();
  await expect(page.getByRole("link", { name: "내 신청" })).toHaveCount(0);
});

test("모바일 머리글의 메뉴 이름은 한 줄로 보이고 가로 넘침이 없다", async ({ page }) => {
  const loginId = newLoginId();
  await ApiActor.signup(loginId, PASSWORD);
  await page.setViewportSize({ width: 375, height: 812 });
  await loginInBrowser(page, loginId, PASSWORD);
  await page.getByRole("link", { name: "내 신청" }).click();
  await expect(page.getByRole("heading", { name: "내 신청" })).toBeVisible();
  for (const name of ["회차 목록", "내 신청", "회원 탈퇴"]) {
    const link = page.getByRole("banner").getByRole("link", { name });
    expect(await link.evaluate((el) => {
        // 요소가 flex 항목이면 getClientRects 는 늘 1개라, 글자 줄 상자의 서로 다른 top 수로 줄 수를 센다
        const range = document.createRange();
        range.selectNodeContents(el);
        return new Set([...range.getClientRects()].map((r) => Math.round(r.top))).size;
      }), name).toBe(1);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
