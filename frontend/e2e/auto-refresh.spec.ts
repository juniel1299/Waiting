import { chromium, expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

/** 프런트 빌드와 같은 값 (frontend/src/lib/useVisiblePolling.ts 기본 5초). */
const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);
/** 즉시 재조회 판정: 폴링 간격보다 확실히 짧아야 "다음 폴링" 이 아니라 즉시 조회로 반영된 것이다. */
const IMMEDIATE_MS = 1500;

let operator: ApiActor;

test.beforeAll(async () => {
  operator = await ApiActor.login("operator");
});

test.afterAll(async () => {
  await operator.dispose();
});

/**
 * 정원 1 회차: bob 확정(API) → alice(A 화면)가 대기 1번. bob(B 화면)은 상세를 열어 둔다.
 * B 는 A 와 쿠키·스토리지는 물론 브라우저 프로세스도 공유하지 않도록 별도 Chromium 으로 띄운다.
 */
async function setUp(page: Page) {
  const sessionId = await operator.createSession(uniqueTitle("자동 갱신"), 1);
  const bobApi = await ApiActor.login("bob");
  expect((await bobApi.reserve(sessionId)).status()).toBe(201);
  await bobApi.dispose();

  await loginInBrowser(page, "alice");
  await page.goto(`/sessions/${sessionId}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  const bBrowser = await chromium.launch();
  const bContext = await bBrowser.newContext({ baseURL: test.info().project.use.baseURL, locale: "ko-KR" });
  const bPage = await bContext.newPage();
  await loginInBrowser(bPage, "bob");
  await bPage.goto(`/sessions/${sessionId}`);
  await expect(bPage.getByTestId("my-reservation")).toHaveText("예약 확정");
  return { sessionId, bBrowser, bPage };
}

/** A 화면의 페이지 이동(새로고침 포함) 수. 0 이면 A 는 다시 불러오지 않은 것이다. */
function countNavigations(page: Page) {
  const counter = { value: 0 };
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) {
      counter.value += 1;
    }
  });
  return counter;
}

/** B 가 화면에서 확정 예약을 취소하고, 서버 응답을 받은 시각(ms)을 돌려준다. */
async function cancelInB(bPage: Page): Promise<number> {
  const response = bPage.waitForResponse((r) => r.url().includes("/cancel") && r.request().method() === "POST");
  await bPage.getByRole("button", { name: "예약 취소" }).click();
  expect((await response).status()).toBe(200);
  return Date.now();
}

function countDetailRequests(page: Page, sessionId: number) {
  const counter = { value: 0 };
  page.on("request", (r) => {
    if (r.method() === "GET" && new URL(r.url()).pathname === `/api/sessions/${sessionId}`) {
      counter.value += 1;
    }
  });
  return counter;
}

async function setVisibility(page: Page, state: "visible" | "hidden") {
  await page.evaluate((s) => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => s });
    Object.defineProperty(document, "hidden", { configurable: true, get: () => s === "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
  }, state);
}

function record(label: string, ms: number) {
  test.info().annotations.push({ type: label, description: `${ms}ms` });
  console.log(`[measure] ${label}: ${ms}ms (poll ${POLL_MS}ms)`);
}

test("A 가 대기 1번일 때 B 가 화면에서 취소하면, A 는 새로고침 없이 다음 폴링에 확정을 본다", async ({ page }) => {
  const { bBrowser, bPage } = await setUp(page);
  const navigations = countNavigations(page);

  const cancelledAt = await cancelInB(bPage);
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정", { timeout: POLL_MS + 3000 });
  const elapsed = Date.now() - cancelledAt;
  record("폴링 반영 시간", elapsed);

  expect(elapsed).toBeLessThanOrEqual(POLL_MS + 1500);
  expect(navigations.value, "A 화면 새로고침·이동").toBe(0);
  await expect(page.getByRole("status")).toContainText("대기 순번이 되어 예약이 확정됐어요");
  await expect(page.getByTestId("remaining")).toHaveText("0 / 1");
  await bBrowser.close();
});

test("탭이 숨겨진 동안은 조회하지 않고, 다시 보이면 즉시 조회해 반영한다", async ({ page }) => {
  const { sessionId, bBrowser, bPage } = await setUp(page);
  const requests = countDetailRequests(page, sessionId);

  await setVisibility(page, "hidden");
  requests.value = 0;
  await cancelInB(bPage);
  await page.waitForTimeout(POLL_MS + 1500);
  expect(requests.value, "숨김 동안 폴링 요청").toBe(0);
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  const shownAt = Date.now();
  await setVisibility(page, "visible");
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정", { timeout: IMMEDIATE_MS });
  record("탭 복귀 반영 시간", Date.now() - shownAt);
  expect(requests.value).toBeGreaterThanOrEqual(1);
  await bBrowser.close();
});

test("오프라인 동안은 조회를 멈추고 안내는 한 번만, 다시 연결되면 즉시 조회해 반영한다", async ({ page, context }) => {
  const { sessionId, bBrowser, bPage } = await setUp(page);
  const requests = countDetailRequests(page, sessionId);

  await context.setOffline(true);
  requests.value = 0;
  await cancelInB(bPage);
  await page.waitForTimeout(POLL_MS * 2 + 1000);

  const offlineNotice = page.getByText("연결이 끊겼어요");
  await expect(offlineNotice).toHaveCount(1);
  expect(requests.value, "오프라인 동안 조회 요청").toBe(0);
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  const onlineAt = Date.now();
  await context.setOffline(false);
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정", { timeout: IMMEDIATE_MS });
  record("네트워크 복구 반영 시간", Date.now() - onlineAt);
  await expect(offlineNotice).toHaveCount(0);
  await bBrowser.close();
});

test("화면이 보이는 동안 폴링 간격대로 조회한다", async ({ page }) => {
  const sessionId = await operator.createSession(uniqueTitle("폴링 간격"), 3);
  await loginInBrowser(page, "alice");
  const requests = countDetailRequests(page, sessionId);
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByTestId("remaining")).toHaveText("3 / 3");

  requests.value = 0;
  await page.waitForTimeout(POLL_MS * 3 + 500);
  // 3 번 폴링 (타이머 오차로 2~4 허용)
  expect(requests.value).toBeGreaterThanOrEqual(2);
  expect(requests.value).toBeLessThanOrEqual(4);
});
