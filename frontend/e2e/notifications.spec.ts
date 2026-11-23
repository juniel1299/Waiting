import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 커뮤니티 3단계 화면: 머리글 알림 버튼(읽지 않은 수를 글자로), 알림함(읽음 상태 색+글자), 열기 → 대상으로 이동·읽음, 모두 읽음.
async function idOf(actor: ApiActor) {
  return (await (await actor.get("/api/auth/me")).json()).id as number;
}

test("멘션 알림이 머리글에 뜨고, 알림함에서 열면 댓글로 이동하고 읽음이 된다", async ({ page }) => {
  const alice = await ApiActor.login("alice");
  const bob = await ApiActor.login("bob");
  const bobId = await idOf(bob);
  await bob.post("/api/me/notifications/read-all");
  await bob.dispose();
  const title = uniqueTitle("알림 글");
  const post = (await (await alice.post("/api/boards/community/posts", { title, body: "본문" })).json()).id as number;

  await loginInBrowser(page, "bob");
  const bell = page.getByRole("banner").getByTestId("notification-bell");
  await expect(bell).toHaveAccessibleName("알림, 읽지 않은 알림 없음");

  const comment = (await (await alice.post(`/api/posts/${post}/comments`, { body: `@[밥#${bobId}] 확인해 주세요` })).json())
    .id as number;
  await alice.dispose();
  // 탭으로 돌아오면 30초를 기다리지 않고 바로 다시 읽는다
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await expect(bell).toHaveAccessibleName("알림, 읽지 않은 알림 1개");

  await bell.click();
  await expect(page).toHaveURL(/\/me\/notifications$/);
  const item = page.getByTestId("notification").filter({ hasText: title });
  await expect(item).toContainText("읽지 않음");
  await expect(item).toContainText("댓글에서 회원님을 언급했어요");
  await item.getByRole("button", { name: "열기" }).click();

  await expect(page).toHaveURL(new RegExp(`/board/${post}#comment-${comment}$`));
  await expect(page.locator(`#comment-${comment}`)).toBeFocused();
  await expect(bell).toHaveAccessibleName("알림, 읽지 않은 알림 없음");
  await page.goto("/me/notifications");
  await expect(page.getByTestId("notification").filter({ hasText: title })).toContainText("읽음");
});

test("대기 승격 알림을 열면 회차로 가고, 모두 읽음으로 정리한다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const carol = await ApiActor.login("carol");
  const bob = await ApiActor.login("bob");
  const bobId = await idOf(bob);
  await bob.post("/api/me/notifications/read-all");
  const sessionTitle = uniqueTitle("승격 알림");
  const sessionId = await operator.createSession(sessionTitle, 1);
  const held = (await (await carol.reserve(sessionId)).json()).id as number;
  await bob.waitlist(sessionId);
  await carol.cancel(held);
  await Promise.all([operator.dispose(), carol.dispose(), bob.dispose()]);

  await loginInBrowser(page, "bob");
  await page.goto("/me/notifications");
  const item = page.getByTestId("notification").filter({ hasText: sessionTitle });
  await expect(item).toContainText(`대기하던 ‘${sessionTitle}’ 회차가 확정됐어요.`);
  await item.getByRole("button", { name: "열기" }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}$`));

  // 읽지 않은 알림을 하나 더 만든 뒤 모두 읽음
  const alice = await ApiActor.login("alice");
  await alice.post("/api/boards/community/posts", { title: uniqueTitle("모두 읽음"), body: `@[밥#${bobId}]` });
  await alice.dispose();
  await page.goto("/me/notifications");
  await expect(page.getByText("읽지 않은 알림 1개")).toBeVisible();
  await page.getByRole("button", { name: "모두 읽음" }).click();
  await expect(page.getByRole("status").filter({ hasText: "모든 알림을 읽음으로 표시했어요." })).toBeVisible();
  await expect(page.getByText("읽지 않은 알림 0개")).toBeVisible();
  await expect(page.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
});

test("글이 지워지면 그 글의 알림도 알림함에서 사라진다", async ({ page }) => {
  const alice = await ApiActor.login("alice");
  const bob = await ApiActor.login("bob");
  const bobId = await idOf(bob);
  await bob.post("/api/me/notifications/read-all");
  const title = uniqueTitle("지울 글");
  const post = (await (await alice.post("/api/boards/community/posts", { title, body: `@[밥#${bobId}]` })).json()).id;

  await loginInBrowser(page, "bob");
  await page.goto("/me/notifications");
  await expect(page.getByTestId("notification").filter({ hasText: title })).toBeVisible();
  await alice.delete(`/api/posts/${post}`);
  await alice.dispose();
  await page.reload();
  await expect(page.getByTestId("notification").filter({ hasText: title })).toHaveCount(0);
  await bob.dispose();
});
