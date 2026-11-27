import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 채팅 이전 기록: 120건 중 최근 50건 → '이전 메시지 더 보기'로 과거를 붙여도 보던 메시지가 제자리, 끝까지 읽으면 '처음 메시지'.
// 위를 보는 동안 새 메시지가 오면 스크롤은 그대로 두고 '새 메시지 N개' 안내, 누르면 맨 아래로. 중복 없이 전체 121건.
const label = (i: number) => `기록 ${String(i).padStart(3, "0")}`;

test("이전 기록을 여러 번 불러와도 보던 위치가 유지되고, 위를 보는 동안 온 새 메시지는 안내로 알린다", async ({ page }) => {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("기록 모임"), intro: "이전 기록", place: "온라인", mode: "ONLINE" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  await operator.dispose();
  const bobApi = await ApiActor.login("bob");
  await bobApi.post(`/api/clubs/${created.id}/join`);
  await bobApi.dispose();
  for (let i = 1; i <= 120; i++) {
    expect((await alice.post(`/api/clubs/${created.id}/chat`, { body: label(i) })).status()).toBe(201);
  }

  await loginInBrowser(page, "bob");
  await page.goto(`/clubs/${created.id}/chat`);
  const log = page.getByRole("log", { name: "채팅 메시지" });
  await expect(log.getByText(label(120))).toBeVisible();
  await expect(log.getByText(label(71))).toHaveCount(1);
  await expect(log.getByText(label(70))).toHaveCount(0);

  // 첫 번째 과거 페이지: 맨 위에 있던 '기록 071' 이 화면에서 같은 자리에 남는다
  await log.evaluate((el) => (el.scrollTop = 0));
  const anchor = log.getByText(label(71));
  const y0 = (await anchor.boundingBox())!.y;
  await page.getByRole("button", { name: "이전 메시지 더 보기" }).click();
  await expect(log.getByText(label(21))).toHaveCount(1);
  expect(Math.abs((await anchor.boundingBox())!.y - y0)).toBeLessThan(4);

  // 위를 보고 있는 동안 새 메시지: 스크롤은 그대로, 안내만
  const fresh = uniqueTitle("새 소식");
  expect((await alice.post(`/api/clubs/${created.id}/chat`, { body: fresh })).status()).toBe(201);
  const notice = page.getByRole("button", { name: /새 메시지 1개/ });
  await expect(notice).toBeVisible({ timeout: 15_000 });
  expect(Math.abs((await anchor.boundingBox())!.y - y0)).toBeLessThan(4);

  // 두 번째 과거 페이지 → 끝
  await log.evaluate((el) => (el.scrollTop = 0));
  await page.getByRole("button", { name: "이전 메시지 더 보기" }).click();
  await expect(log.getByText(label(1), { exact: true })).toHaveCount(1);
  await expect(page.getByText("처음 메시지예요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "이전 메시지 더 보기" })).toHaveCount(0);

  await notice.click();
  await expect(log.getByText(fresh)).toBeInViewport();
  await expect(notice).toHaveCount(0);
  await expect(log.getByRole("listitem")).toHaveCount(121);
  await alice.dispose();
});

test("이전 기록을 불러오지 못하면 다시 시도할 수 있다", async ({ page }) => {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("실패 모임"), intro: "실패", place: "온라인", mode: "ONLINE" })).json();
  await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version });
  await operator.dispose();
  for (let i = 1; i <= 55; i++) {
    await alice.post(`/api/clubs/${created.id}/chat`, { body: label(i) });
  }
  await alice.dispose();
  await loginInBrowser(page, "alice");
  await page.goto(`/clubs/${created.id}/chat`);
  const log = page.getByRole("log", { name: "채팅 메시지" });
  await expect(log.getByText(label(55))).toBeVisible();

  let fail = true;
  await page.route(/\/api\/clubs\/\d+\/chat\?before=/, (route) =>
    fail ? route.fulfill({ status: 500, contentType: "application/json", body: '{"code":"INTERNAL_ERROR","message":"x"}' }) : route.continue(),
  );
  await page.getByRole("button", { name: "이전 메시지 더 보기" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "이전 메시지를 불러오지 못했어요" })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(log.getByText(label(1), { exact: true })).toHaveCount(1);
  await expect(page.getByText("처음 메시지예요.")).toBeVisible();
});
