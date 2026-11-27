import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 확장 6단계 모임 채팅: 회원끼리 주고받기(몇 초 간격 이어 받기), 본인 메시지 삭제는 표시만 남김, 회원이 아니면 안내.
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function chatClub() {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("채팅 모임"), intro: "채팅 확인", place: "온라인", mode: "ONLINE" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  const bob = await ApiActor.login("bob");
  await bob.post(`/api/clubs/${created.id}/join`);
  await Promise.all([alice.dispose(), operator.dispose(), bob.dispose()]);
  return created.id as number;
}

test("두 회원이 채팅을 주고받고, 지운 메시지는 상대 화면에서도 '삭제된 메시지'로 바뀐다", async ({ page, browser }) => {
  const club = await chatClub();
  const text = uniqueTitle("안녕");
  await loginInBrowser(page, "alice");
  await page.goto(`/clubs/${club}`);
  await page.getByRole("navigation", { name: "모임 안 메뉴" }).getByRole("link", { name: "채팅" }).click();
  await expect(page.getByRole("heading", { name: /채팅/ })).toBeVisible();
  await visible(page, "메시지").fill(text);
  await visible(page, "메시지").press("Enter");
  const log = page.getByRole("log", { name: "채팅 메시지" });
  await expect(log.getByText(text)).toBeVisible();

  const bobContext = await browser.newContext();
  const bob = await bobContext.newPage();
  await loginInBrowser(bob, "bob");
  await bob.goto(`/clubs/${club}/chat`);
  const bobLog = bob.getByRole("log", { name: "채팅 메시지" });
  await expect(bobLog.getByText(text)).toBeVisible();
  const reply = uniqueTitle("반가워요");
  await visible(bob, "메시지").fill(reply);
  await bob.getByRole("button", { name: "보내기" }).click();
  await expect(bobLog.getByText(reply)).toBeVisible();

  // 상대가 보낸 메시지는 몇 초 안에 이어 받는다
  await expect(log.getByText(reply)).toBeVisible({ timeout: 15_000 });
  await bobLog.getByRole("listitem").filter({ hasText: reply }).getByRole("button", { name: "삭제" }).click();
  await expect(bobLog.getByRole("listitem").filter({ hasText: "삭제된 메시지예요" })).toHaveCount(1);
  await expect(log.getByText("삭제된 메시지예요")).toBeVisible({ timeout: 15_000 });
  await expect(log.getByText(reply)).toHaveCount(0);
  await bobContext.close();
});

test("모임 회원이 아니면 채팅 대신 안내가 보인다", async ({ page }) => {
  const club = await chatClub();
  await loginInBrowser(page, "carol");
  await page.goto(`/clubs/${club}/chat`);
  await expect(page.getByRole("alert").filter({ hasText: "모임 회원만" })).toBeVisible();
  await expect(page.getByLabel("메시지", { exact: true })).toHaveCount(0);
});
