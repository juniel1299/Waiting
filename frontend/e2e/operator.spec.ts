import { expect, test } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle, menuLink } from "./support";

test("운영자가 회차를 만들고 '전체 회차' 탭에서 찾아 확정 명단을 확인한다", async ({ page }) => {
  const title = uniqueTitle("운영자 등록");

  await loginInBrowser(page, "operator");
  await expect(page.getByRole("heading", { name: "운영자 · 사용량" })).toBeVisible();
  await menuLink(page, "회차", "회차 만들기");
  await page.getByLabel("제목").fill(title);
  await page.getByLabel("시작 일시 (서울)").fill("2030-03-02T19:30");
  await page.getByLabel("장소").fill("판교 스터디룸");
  await page.getByLabel("정원").fill("4");
  await page.getByRole("button", { name: "회차 만들기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  const sessionId = Number(new URL(page.url()).pathname.split("/").pop());

  await menuLink(page, "관리", "대시보드");
  await page.getByRole("navigation", { name: "운영자 메뉴" }).getByRole("link", { name: "전체 회차" }).click();
  await expect(page).toHaveURL(/\/operator\/sessions$/);
  const search = page.getByLabel("검색어", { exact: true }).filter({ visible: true });
  await search.fill(title);
  await search.press("Enter");
  const row = page.locator(".cards .card:visible").filter({ hasText: title });
  await expect(row).toContainText("2030. 3. 2.");
  await expect(row).toContainText("오후 7:30");
  await expect(row).toContainText("0 / 정원 4");

  const alice = await ApiActor.login("alice");
  expect((await alice.reserve(sessionId)).status()).toBe(201);
  await alice.dispose();

  await row.getByRole("link", { name: `${title} 명단·삭제` }).click();
  await expect(page.getByRole("heading", { name: `회차 관리 · ${title}` })).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: "alice" })).toContainText("앨리스");
});

test("참가자가 운영자 명단 주소에 직접 들어가도 명단을 볼 수 없다 (서버 403)", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const sessionId = await operator.createSession(uniqueTitle("명단 보호"), 3);
  const carol = await ApiActor.login("carol");
  expect((await carol.reserve(sessionId)).status()).toBe(201);
  await Promise.all([operator.dispose(), carol.dispose()]);

  await loginInBrowser(page, "alice");
  await expect(page).toHaveURL(/\/sessions$/);

  const rosterResponse = page.waitForResponse((r) => r.url().endsWith(`/api/sessions/${sessionId}/roster`));
  await page.goto(`/operator/sessions/${sessionId}`);
  expect((await rosterResponse).status()).toBe(403);

  await expect(appAlert(page)).toContainText("권한이 없습니다");
  await expect(page.getByRole("table")).toHaveCount(0);
  await expect(page.getByText("carol")).toHaveCount(0);
  await expect(page.getByText("캐롤")).toHaveCount(0);
});

test("참가자가 운영자 화면에 직접 들어가면 등록 폼 대신 권한 안내가 나온다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  await expect(page).toHaveURL(/\/sessions$/);

  await page.goto("/operator");
  await expect(appAlert(page)).toContainText("권한이 없습니다");
  await expect(page.getByRole("button", { name: "회차 등록" })).toHaveCount(0);
});
