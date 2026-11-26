import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, menuLink, uniqueTitle } from "./support";

// 확장 2단계 화면: 프로필(닉네임 중복 확인·소개·관심 태그)과 공개 프로필(공개 항목만), 회차·모임 즐겨찾기와 내 즐겨찾기 목록.
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

test("프로필에서 닉네임 중복을 확인하고 소개·태그를 저장하면, 다른 회원에게는 공개 항목만 보인다", async ({ page }) => {
  const taken = `별${Date.now().toString(36).slice(-5)}`;
  const carol = await ApiActor.login("carol");
  expect((await carol.patch("/api/me/profile", { nickname: taken })).status()).toBe(200);
  await carol.dispose();

  await loginInBrowser(page, "bob");
  await menuLink(page, "내 정보", "프로필");
  await expect(page.getByRole("heading", { name: "프로필" })).toBeVisible();
  await visible(page, "공개 닉네임").fill(taken.toUpperCase());
  await page.getByRole("button", { name: "중복 확인" }).click();
  await expect(page.getByText("이미 다른 회원이 쓰는 닉네임이에요.")).toBeVisible();

  const mine = `밥${Date.now().toString(36).slice(-5)}`;
  await visible(page, "공개 닉네임").fill(mine);
  await page.getByRole("button", { name: "중복 확인" }).click();
  await expect(page.getByText("쓸 수 있는 닉네임이에요.")).toBeVisible();
  await visible(page, "소개").fill("주말 알고리즘 스터디를 찾고 있어요");
  await visible(page, "관심 태그").fill("알고리즘, 자바, 알고리즘");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("status").filter({ hasText: "프로필을 저장했어요" })).toBeVisible();
  await expect(page.getByTestId("profile-tags").getByRole("listitem")).toHaveText(["알고리즘", "자바"]);

  const bobId = (await (await page.request.get("/api/auth/me")).json()).id as number;
  const alice = await ApiActor.login("alice");
  const shown = await (await alice.get(`/api/profiles/${bobId}`)).json();
  await alice.dispose();
  expect(Object.keys(shown).sort()).toEqual(["bio", "id", "nickname", "tags"]);

  await page.goto(`/profiles/${bobId}`);
  await expect(page.getByRole("heading", { name: mine })).toBeVisible();
  await expect(page.getByText("주말 알고리즘 스터디를 찾고 있어요")).toBeVisible();
  await expect(page.getByText("bob", { exact: true })).toHaveCount(0);
});

test("회차와 모임을 즐겨찾기하면 내 활동 > 즐겨찾기에 보이고, 거기서 해제할 수 있다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const title = uniqueTitle("즐겨찾기 회차");
  const sessionId = await operator.createSession(title, 3);
  const clubName = uniqueTitle("즐겨찾기 모임");
  const club = await (await operator.post("/api/clubs", { name: clubName, intro: "소개", place: "온라인", mode: "ONLINE" })).json();
  await operator.post(`/api/operator/clubs/${club.id}/approve`, { version: club.version });
  await operator.dispose();

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  const star = page.getByRole("button", { name: "즐겨찾기" });
  await expect(star).toHaveAttribute("aria-pressed", "false");
  await star.click();
  await expect(star).toHaveAttribute("aria-pressed", "true");

  await page.goto(`/clubs/${club.id}`);
  await page.getByRole("button", { name: "즐겨찾기" }).click();
  await expect(page.getByRole("button", { name: "즐겨찾기" })).toHaveAttribute("aria-pressed", "true");

  await menuLink(page, "내 활동", "즐겨찾기");
  const main = page.getByRole("main");
  await expect(main.getByRole("link", { name: title })).toBeVisible();
  await expect(main.getByRole("link", { name: clubName })).toBeVisible();
  await main.getByRole("listitem").filter({ hasText: title }).getByRole("button", { name: "즐겨찾기 해제" }).click();
  await expect(main.getByRole("link", { name: title })).toHaveCount(0);
  await page.reload();
  await expect(main.getByRole("link", { name: clubName })).toBeVisible();
  await expect(main.getByRole("link", { name: title })).toHaveCount(0);
});
