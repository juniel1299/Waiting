import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

let operator: ApiActor;

test.beforeAll(async () => {
  operator = await ApiActor.login("operator");
});

test.afterAll(async () => {
  await operator.dispose();
});

test("대기 1번인 참가자는 확정자가 취소하면 새로고침 후 확정 상태를 본다", async ({ page }) => {
  const sessionId = await operator.createSession(uniqueTitle("대기 승격"), 1);
  const bob = await ApiActor.login("bob");
  const bobReservation = await bob.reserve(sessionId);
  expect(bobReservation.status()).toBe(201);

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByTestId("session-status")).toHaveText("마감");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");

  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByRole("status")).toContainText("대기 1번으로 신청했어요");
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");
  await expect(page.getByTestId("waiting-count")).toHaveText("1명");

  expect((await bob.cancel((await bobReservation.json()).id)).status()).toBe(200);

  await page.reload();
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await expect(page.getByTestId("remaining")).toHaveText("0 / 1");
  await expect(page.getByTestId("waiting-count")).toHaveText("0명");
  await expect(page.getByRole("button", { name: "예약 취소" })).toBeEnabled();

  await bob.dispose();
});

test("앞 사람이 대기를 취소하면 순번이 당겨지고, 대기를 취소하면 다시 신청할 수 있다", async ({ page }) => {
  const sessionId = await operator.createSession(uniqueTitle("대기 순번"), 1);
  const bob = await ApiActor.login("bob");
  expect((await bob.reserve(sessionId)).status()).toBe(201);
  const carol = await ApiActor.login("carol");
  const carolWaiting = await carol.waitlist(sessionId);
  expect(carolWaiting.status()).toBe(201);

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 2번");

  expect((await carol.cancel((await carolWaiting.json()).id)).status()).toBe(200);
  await page.reload();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  await page.getByRole("button", { name: "대기 취소" }).click();
  await expect(page.getByRole("status")).toContainText("대기를 취소했어요");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 없음");
  await expect(page.getByRole("button", { name: "대기 신청" })).toBeEnabled();

  await Promise.all([bob.dispose(), carol.dispose()]);
});
