import { chromium, expect, test, type Page } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle } from "./support";

const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);

let operatorApi: ApiActor;

test.beforeAll(async () => {
  operatorApi = await ApiActor.login("operator");
});

test.afterAll(async () => {
  await operatorApi.dispose();
});

/** 운영자는 참가자와 다른 Chromium 프로세스로 띄운다. */
async function openOperatorRoster(sessionId: number) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL, locale: "ko-KR" });
  const page = await context.newPage();
  await loginInBrowser(page, "operator");
  await page.goto(`/operator/sessions/${sessionId}`);
  return { browser, page };
}

async function changeCapacity(page: Page, capacity: number) {
  await page.getByLabel("새 정원").fill(String(capacity));
  await page.getByRole("button", { name: "정원 변경" }).click();
}

test("운영자가 정원을 늘리면 대기 중인 참가자 상세가 새로고침 없이 확정으로 바뀐다", async ({ page }) => {
  const sessionId = await operatorApi.createSession(uniqueTitle("정원 증설"), 1);
  const bob = await ApiActor.login("bob");
  expect((await bob.reserve(sessionId)).status()).toBe(201);
  await bob.dispose();

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");
  let navigations = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigations += 1;
  });

  const operator = await openOperatorRoster(sessionId);
  await expect(operator.page.getByTestId("capacity")).toContainText("정원 1명");
  await changeCapacity(operator.page, 2);
  await expect(operator.page.getByRole("status")).toContainText("정원을 2명으로 바꿨어요. 대기자 1명이 확정됐어요.");
  await expect(operator.page.getByTestId("capacity")).toContainText("정원 2명");
  const changedAt = Date.now();

  await expect(page.getByTestId("my-reservation")).toHaveText("확정", { timeout: POLL_MS + 3000 });
  console.log(`[measure] 증설 → 참가자 화면 확정: ${Date.now() - changedAt}ms (poll ${POLL_MS}ms)`);
  await expect(page.getByTestId("remaining")).toHaveText("0 / 2");
  expect(navigations, "참가자 화면 새로고침·이동").toBe(0);
  await operator.browser.close();
});

test("확정 인원보다 적게 줄이면 거절을 안내하고 정원은 그대로다", async () => {
  const sessionId = await operatorApi.createSession(uniqueTitle("정원 축소 거절"), 2);
  for (const who of ["alice", "bob"]) {
    const actor = await ApiActor.login(who);
    expect((await actor.reserve(sessionId)).status()).toBe(201);
    await actor.dispose();
  }

  const operator = await openOperatorRoster(sessionId);
  await expect(operator.page.getByTestId("capacity")).toContainText("정원 2명");
  await changeCapacity(operator.page, 1);

  await expect(appAlert(operator.page)).toContainText("현재 확정 인원보다 적게 정원을 줄일 수 없습니다");
  await operator.page.reload();
  await expect(operator.page.getByTestId("capacity")).toContainText("정원 2명");
  await operator.browser.close();
});
