import { chromium, expect, test, type Page } from "@playwright/test";

import { appAlert } from "./support";

const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);

async function startDemo(page: Page): Promise<number> {
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  return Number(new URL(page.url()).pathname.split("/").pop());
}

test("로그인 화면 없이 체험 시작 → 대기 1번 → 샘플 취소 → 새로고침 없이 예약 확정", async ({ page }) => {
  const visited: string[] = [];
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) visited.push(new URL(frame.url()).pathname);
  });

  await startDemo(page);
  await expect(page.getByTestId("session-status")).toHaveText("마감");
  await expect(page.getByTestId("remaining")).toHaveText("0 / 2");
  await expect(page.getByText(/체험 데이터는 .+에 삭제돼요/)).toBeVisible();

  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  const navigationsBefore = visited.length;
  await page.getByRole("button", { name: "샘플 참가자 취소" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await expect(page.getByRole("status")).toContainText("예약이 확정됐어요");
  await expect(page.getByTestId("remaining")).toHaveText("0 / 2");
  await expect(page.getByRole("button", { name: "샘플 참가자 취소" })).toBeDisabled();

  expect(visited.length, "샘플 취소 뒤 페이지 이동·새로고침").toBe(navigationsBefore);
  expect(visited).not.toContain("/login");
});

test("별도 브라우저의 두 방문자는 서로의 체험에 영향을 주지 않는다", async () => {
  const baseURL = test.info().project.use.baseURL;
  const open = async () => {
    const browser = await chromium.launch();
    const page = await (await browser.newContext({ baseURL, locale: "ko-KR" })).newPage();
    return { browser, page, sessionId: await startDemo(page) };
  };
  const a = await open();
  const b = await open();
  expect(a.sessionId).not.toBe(b.sessionId);

  for (const visitor of [a, b]) {
    await visitor.page.getByRole("button", { name: "대기 신청" }).click();
    await expect(visitor.page.getByTestId("my-reservation")).toHaveText("대기 1번");
  }

  await a.page.getByRole("button", { name: "샘플 참가자 취소" }).click();
  await expect(a.page.getByTestId("my-reservation")).toHaveText("예약 확정");

  // B 는 자동 갱신이 한 번 이상 돈 뒤에도 그대로여야 한다
  await b.page.waitForTimeout(POLL_MS + 1000);
  await expect(b.page.getByTestId("my-reservation")).toHaveText("대기 1번");
  await expect(b.page.getByTestId("remaining")).toHaveText("0 / 2");
  await expect(b.page.getByTestId("waiting-count")).toHaveText("1명");
  await expect(b.page.getByRole("button", { name: "샘플 참가자 취소" })).toBeEnabled();

  // B 가 A 의 체험 회차 주소로 들어가도 볼 수 없다
  await b.page.goto(`/sessions/${a.sessionId}`);
  await expect(appAlert(b.page)).toContainText("삭제되었거나 찾을 수 없는 회차입니다");

  await Promise.all([a.browser.close(), b.browser.close()]);
});
