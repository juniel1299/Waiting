import { expect, request, test } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser } from "./support";

const PASSWORD = "owner-pass-1!";
const baseURL = `http://localhost:${process.env.E2E_PORT ?? 3000}`;

function newLoginId(prefix: string) {
  return `${prefix}_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
}

test("일반 회원이 회차를 만들고, 다른 회원이 예약·대기하면 생성자가 명단·정원·삭제를 관리하고, 삭제 뒤 상세는 안내를 보인다", async ({
  page,
  browser,
}) => {
  const ownerId = newLoginId("ow");
  await ApiActor.signup(ownerId, PASSWORD);
  const memberId = newLoginId("mb");
  const member = await ApiActor.signup(memberId, PASSWORD);
  const waiter = await ApiActor.signup(newLoginId("wt"), PASSWORD);

  await loginInBrowser(page, ownerId, PASSWORD);
  await page.getByRole("link", { name: "회차 만들기" }).click();
  await expect(page).toHaveURL(/\/sessions\/new$/);
  const title = `생성자 e2e ${Date.now().toString(36)}`;
  await page.getByLabel("제목").fill(title);
  await page.getByLabel("시작 일시 (서울)").fill("2031-03-02T19:30");
  await page.getByLabel("장소").fill("판교 스터디룸");
  await page.getByLabel("정원").fill("1");
  await page.getByRole("button", { name: "회차 만들기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  const sessionId = Number(new URL(page.url()).pathname.split("/").pop());
  await expect(page.getByRole("heading", { name: title })).toBeVisible();

  // 다른 회원: 예약·대기. 관리 API 를 직접 불러도 403
  expect((await member.reserve(sessionId)).status()).toBe(201);
  expect((await waiter.waitlist(sessionId)).status()).toBe(201);
  const intruder = await request.newContext({ baseURL });
  const csrf = await (await intruder.get("/api/auth/csrf")).json();
  await intruder.post("/api/auth/login", { data: { loginId: memberId, password: PASSWORD }, headers: { [csrf.headerName]: csrf.token } });
  const csrf2 = await (await intruder.get("/api/auth/csrf")).json();
  expect((await intruder.delete(`/api/sessions/${sessionId}`, { headers: { [csrf2.headerName]: csrf2.token } })).status()).toBe(403);
  expect((await intruder.get(`/api/sessions/${sessionId}/roster`)).status()).toBe(403);
  await intruder.dispose();

  // 생성자: 관리 화면에서 명단·정원 변경(대기자 승격)
  await page.goto(`/sessions/${sessionId}`);
  await page.getByRole("link", { name: "회차 관리" }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}/manage$`));
  await expect(page.getByRole("row").filter({ hasText: memberId })).toBeVisible();
  await page.getByLabel("새 정원").fill("2");
  await page.getByRole("button", { name: "정원 변경" }).click();
  await expect(page.getByRole("status")).toContainText("대기자 1명이 확정됐어요");

  // 삭제: 확인창에 연결 신청 삭제·복구 불가 안내
  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("신청");
    expect(dialog.message()).toContain("복구할 수 없어요");
    await dialog.accept();
  });
  await page.getByRole("button", { name: "회차 삭제" }).click();
  await expect(page).toHaveURL(/\/sessions$/);

  // 다른 회원이 삭제된 회차를 열면 안내와 목록 링크
  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await loginInBrowser(otherPage, memberId, PASSWORD);
  await otherPage.goto(`/sessions/${sessionId}`);
  await expect(appAlert(otherPage)).toContainText("삭제되었거나 찾을 수 없는 회차입니다");
  await otherPage.getByRole("link", { name: "회차 목록으로" }).click();
  await expect(otherPage).toHaveURL(/\/sessions$/);
  await other.close();
  await Promise.all([member.dispose(), waiter.dispose()]);
});

test("다른 회원의 회차 상세에는 관리 링크가 없고, 체험 방문자는 회차 만들기 메뉴가 없다", async ({ page }) => {
  const owner = await ApiActor.signup(newLoginId("o2"), PASSWORD);
  const res = await owner.post("/api/sessions", { title: "남의 회차", startsAt: "2031-04-01T10:00:00Z", location: "x", capacity: 3 });
  const sessionId = (await res.json()).id;
  const viewerId = newLoginId("vw");
  await ApiActor.signup(viewerId, PASSWORD);
  await loginInBrowser(page, viewerId, PASSWORD);
  await page.goto(`/sessions/${sessionId}`);
  await expect(page.getByRole("button", { name: "예약하기" })).toBeVisible();
  await expect(page.getByRole("link", { name: "회차 관리" })).toHaveCount(0);

  const demo = await page.context().browser()!.newContext();
  const demoPage = await demo.newPage();
  await demoPage.goto(baseURL + "/");
  await demoPage.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(demoPage).toHaveURL(/\/sessions\/\d+$/);
  await expect(demoPage.getByRole("link", { name: "회차 만들기" })).toHaveCount(0);
  await demo.close();
  await owner.dispose();
});

test("탈퇴 확인 화면은 내가 만든 회차와 그 신청이 함께 삭제된다고 안내한다", async ({ page }) => {
  const ownerId = newLoginId("lv");
  const owner = await ApiActor.signup(ownerId, PASSWORD);
  const res = await owner.post("/api/sessions", { title: "탈퇴 안내 회차", startsAt: "2031-05-01T10:00:00Z", location: "x", capacity: 3 });
  const member = await ApiActor.signup(newLoginId("lm"), PASSWORD);
  expect((await member.reserve((await res.json()).id)).status()).toBe(201);
  await loginInBrowser(page, ownerId, PASSWORD);
  await page.goto("/account/withdraw");
  await expect(page.getByText("내가 만든 회차(지금 1개)와 그 신청(지금 1건)은 함께 삭제되고 복구할 수 없어요")).toBeVisible();
  await Promise.all([owner.dispose(), member.dispose()]);
});
