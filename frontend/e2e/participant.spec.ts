import { expect, test } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle } from "./support";

let operator: ApiActor;

test.beforeAll(async () => {
  operator = await ApiActor.login("operator");
});

test.afterAll(async () => {
  await operator.dispose();
});

test("참가자가 예약하고 새로고침해도 유지되며 취소하면 잔여석이 돌아온다", async ({ page }) => {
  const title = uniqueTitle("예약 흐름");
  const sessionId = await operator.createSession(title, 3);

  await loginInBrowser(page, "alice");
  await expect(page).toHaveURL(/\/sessions$/);
  const card = page.getByRole("listitem").filter({ hasText: title });
  await expect(card).toContainText("잔여 3석");
  await card.getByRole("link", { name: title }).click();

  await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}$`));
  await expect(page.getByTestId("remaining")).toHaveText("3 / 3");
  await page.getByRole("button", { name: "예약하기" }).click();
  await expect(page.getByRole("status")).toContainText("예약이 확정됐어요");
  await expect(page.getByTestId("remaining")).toHaveText("2 / 3");
  await expect(page.getByTestId("my-reservation")).toHaveText("확정");

  await page.reload();
  await expect(page.getByTestId("my-reservation")).toHaveText("확정");
  await expect(page.getByTestId("remaining")).toHaveText("2 / 3");

  await page.getByRole("button", { name: "예약 취소" }).click();
  await expect(page.getByRole("status")).toContainText("예약을 취소했어요");
  await expect(page.getByTestId("remaining")).toHaveText("3 / 3");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");
  await expect(page.getByRole("button", { name: "예약하기" })).toBeEnabled();
});

test("화면을 연 뒤 다른 참가자가 마지막 자리를 예약하면 마감 안내가 나온다", async ({ page }) => {
  const title = uniqueTitle("마지막 1석");
  const sessionId = await operator.createSession(title, 2);
  const carol = await ApiActor.login("carol");
  expect((await carol.reserve(sessionId)).status()).toBe(201);

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByTestId("remaining")).toHaveText("1 / 2");

  const bob = await ApiActor.login("bob");
  expect((await bob.reserve(sessionId)).status()).toBe(201);

  await page.getByRole("button", { name: "예약하기" }).click();
  await expect(appAlert(page)).toContainText("정원이 마감됐어요");
  await expect(page.getByTestId("remaining")).toHaveText("0 / 2");
  await expect(page.getByTestId("session-status")).toHaveText("마감");
  await expect(page.getByRole("button", { name: "예약하기" })).toBeDisabled();
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");

  await Promise.all([carol.dispose(), bob.dispose()]);
});

test("다른 곳에서 이미 예약한 뒤 다시 누르면 기존 예약으로 안내한다 (200)", async ({ page }) => {
  const title = uniqueTitle("중복 예약");
  const sessionId = await operator.createSession(title, 3);

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");

  const aliceElsewhere = await ApiActor.login("alice");
  expect((await aliceElsewhere.reserve(sessionId)).status()).toBe(201);

  await page.getByRole("button", { name: "예약하기" }).click();
  await expect(page.getByRole("status")).toContainText("이미 예약된 회차예요");
  await expect(page.getByTestId("my-reservation")).toHaveText("확정");
  await expect(page.getByTestId("remaining")).toHaveText("2 / 3");

  await aliceElsewhere.dispose();
});

test("시작 시각이 지난 뒤 예약하면 시작된 회차로 구분해 안내한다", async ({ page }) => {
  const title = uniqueTitle("곧 시작");
  const startsAt = new Date(Date.now() + 8_000);
  const sessionId = await operator.createSession(title, 3, startsAt);

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByRole("button", { name: "예약하기" })).toBeEnabled();

  await page.waitForTimeout(Math.max(0, startsAt.getTime() - Date.now()) + 500);
  await page.getByRole("button", { name: "예약하기" }).click();

  await expect(appAlert(page)).toContainText("이미 시작된 회차라 예약·취소할 수 없어요");
  await expect(page.getByTestId("session-status")).toHaveText("시작됨");
});
