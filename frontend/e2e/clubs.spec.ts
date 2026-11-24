import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 커뮤니티 4단계 화면: 신청 → 승인 대기(글자 배지) → 반려 사유 → 고쳐서 재신청 → 운영자 승인 → 결과 알림·목록 검색,
// 가입·탈퇴, 데모는 소개만, 운영자 화면의 낡은 버전 승인은 다시 확인 안내.
const nav = (page: Page) => page.getByRole("navigation", { name: "주 메뉴" });

async function clubIdByName(actor: ApiActor, name: string) {
  const mine = await (await actor.get("/api/me/clubs")).json();
  return mine.applications.find((c: { name: string }) => c.name === name) as { id: number; version: number };
}

test("모임을 신청하고 반려 사유를 보고 고쳐 재신청한 뒤 운영자 승인과 알림을 받는다", async ({ page, browser }) => {
  const name = uniqueTitle("독서 모임");
  await loginInBrowser(page, "bob");
  await nav(page).getByRole("link", { name: "모임 목록" }).click();
  await page.getByRole("link", { name: "모임 만들기 신청" }).click();
  await expect(page.getByRole("heading", { name: "모임 만들기 신청" })).toBeVisible();
  await page.getByLabel("모임 이름", { exact: true }).filter({ visible: true }).fill(name);
  await expect(page.getByLabel("모임 이름", { exact: true }).filter({ visible: true })).toHaveValue(name);
  await page.getByLabel("소개", { exact: true }).filter({ visible: true }).fill("한 달에 한 권\n같이 읽어요");
  await page.getByLabel("활동 장소", { exact: true }).filter({ visible: true }).fill("강남");
  await page.getByRole("radio", { name: "온·오프라인" }).check();
  await page.getByRole("button", { name: "신청" }).click();
  await expect(page.getByRole("heading", { name })).toBeVisible();
  await expect(page.locator("article.club-detail .stage .badge").filter({ visible: true })).toHaveText("승인 대기");

  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const applied = await clubIdByName(bob, name);
  await operator.post(`/api/operator/clubs/${applied.id}/reject`, { version: applied.version, reason: "장소를 자세히 적어 주세요" });

  await nav(page).getByRole("link", { name: "내 모임" }).click();
  const card = page.getByTestId("club-application").filter({ hasText: name });
  await expect(card).toContainText("반려됨");
  await expect(card).toContainText("반려 사유: 장소를 자세히 적어 주세요");
  await card.getByRole("link", { name }).click();
  await page.getByRole("link", { name: "고쳐서 다시 신청" }).click();
  await expect(page.getByText("고쳐서 저장하면 다시 승인을 기다려요.")).toBeVisible();
  await page.getByLabel("활동 장소", { exact: true }).filter({ visible: true }).fill("강남역 2번 출구 스터디카페");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.locator("article.club-detail .stage .badge").filter({ visible: true })).toHaveText("승인 대기");

  const opContext = await browser.newContext();
  const opPage = await opContext.newPage();
  await loginInBrowser(opPage, "operator");
  await opPage.goto("/operator/clubs");
  const pending = opPage.getByTestId("pending-club").filter({ hasText: name });
  await expect(pending).toContainText("강남역 2번 출구 스터디카페");
  await pending.getByRole("button", { name: "승인" }).click();
  await expect(opPage.getByRole("status").filter({ hasText: "승인했어요" })).toBeVisible();
  await opContext.close();

  await page.goto("/me/notifications");
  await expect(page.getByTestId("notification").filter({ hasText: name }).first()).toContainText("모임 신청이 승인됐어요");
  await page.goto(`/clubs?q=${encodeURIComponent(name)}`);
  await expect(page.getByText("1개 모임")).toBeVisible();
  await expect(page.getByTestId("club-card").filter({ hasText: name })).toContainText("회원 1명");
  await Promise.all([operator.dispose(), bob.dispose()]);
});

test("승인된 모임에 가입하고 탈퇴한다. 만든 사람은 탈퇴 버튼이 없다", async ({ page }) => {
  const name = uniqueTitle("등산 모임");
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name, intro: "주말 산행", place: "북한산", mode: "OFFLINE" })).json();
  await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version });
  await Promise.all([alice.dispose(), operator.dispose()]);

  await loginInBrowser(page, "carol");
  await page.goto(`/clubs/${created.id}`);
  await page.getByRole("button", { name: "가입하기" }).click();
  await expect(page.getByRole("status").filter({ hasText: "모임에 가입했어요." })).toBeVisible();
  await expect(page.getByText("2명", { exact: true })).toBeVisible();
  await page.goto("/me/clubs");
  await expect(page.getByRole("link", { name })).toBeVisible();
  await page.goto(`/clubs/${created.id}`);
  await page.getByRole("button", { name: "모임 탈퇴" }).click();
  await expect(page.getByRole("status").filter({ hasText: "모임에서 탈퇴했어요." })).toBeVisible();
  await expect(page.getByRole("button", { name: "가입하기" })).toBeVisible();
});

test("운영자가 본 뒤 신청자가 고치면 승인은 다시 확인하라고 안내한다", async ({ page }) => {
  const name = uniqueTitle("경합 모임");
  const bob = await ApiActor.login("bob");
  const created = await (await bob.post("/api/clubs", { name, intro: "소개", place: "온라인", mode: "ONLINE" })).json();

  await loginInBrowser(page, "operator");
  await page.goto("/operator/clubs");
  const pending = page.getByTestId("pending-club").filter({ hasText: name });
  await expect(pending).toBeVisible();
  await bob.patch(`/api/clubs/${created.id}`, { name, intro: "고친 소개", place: "온라인", mode: "ONLINE" });
  await bob.dispose();
  await pending.getByRole("button", { name: "승인" }).click();
  await expect(page.getByRole("status").filter({ hasText: "바뀌었어요" })).toBeVisible();
  await expect(page.getByTestId("pending-club").filter({ hasText: name })).toContainText("고친 소개");
});

test("데모 방문자는 승인된 모임 소개만 보고 가입·신청 버튼이 없다. 모임 홍보는 따로 현재 위치를 알린다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await nav(page).getByRole("link", { name: "모임 홍보" }).click();
  await expect(page.getByRole("heading", { name: "모임 홍보" })).toBeVisible();
  await expect(nav(page).getByRole("link", { name: "모임 홍보" })).toHaveAttribute("aria-current", "page");
  await expect(nav(page).getByRole("link", { name: "모임 목록" })).not.toHaveAttribute("aria-current", "page");
  await expect(nav(page).getByRole("link", { name: "내 모임" })).toHaveCount(0);
  await nav(page).getByRole("link", { name: "모임 목록" }).click();
  await expect(page.getByText("체험 중에는 승인된 모임 소개를 보기만 할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("link", { name: "모임 만들기 신청" })).toHaveCount(0);
  const first = page.getByTestId("club-card").first();
  if (await first.count()) {
    await first.getByRole("link").click();
    await expect(page.getByRole("heading", { level: 2, name: "소개" })).toBeVisible();
    await expect(page.getByRole("button", { name: "가입하기" })).toHaveCount(0);
  }
});
