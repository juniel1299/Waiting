import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 확장 7단계 모임 사진첩: 회원이 올리면 다른 회원도 썸네일을 보고, 본인이 지울 수 있다. 형식이 아니면 안내, 회원이 아니면 안내.
const PNG_4X3 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAIAAAA7ljmRAAAAFUlEQVR4nGPQqDgBRB+2aAARA04OAHDiEzmFaOyDAAAAAElFTkSuQmCC",
  "base64",
);

async function photoClub() {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("사진 모임"), intro: "사진 확인", place: "온라인", mode: "ONLINE" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  const bob = await ApiActor.login("bob");
  await bob.post(`/api/clubs/${created.id}/join`);
  await Promise.all([alice.dispose(), operator.dispose(), bob.dispose()]);
  return created.id as number;
}

test("회원이 사진을 올리면 다른 회원도 썸네일을 보고, 올린 사람은 지울 수 있다. 사진이 아니면 안내", async ({ page, browser }) => {
  const club = await photoClub();
  await loginInBrowser(page, "bob");
  await page.goto(`/clubs/${club}`);
  await page.getByRole("navigation", { name: "모임 안 메뉴" }).getByRole("link", { name: "사진첩" }).click();
  await expect(page.getByRole("heading", { name: "모임 사진첩" })).toBeVisible();

  await page.getByLabel("사진 올리기").setInputFiles({ name: "fake.jpg", mimeType: "image/jpeg", buffer: Buffer.from("<html>not image</html>") });
  await expect(page.getByRole("alert").filter({ hasText: "JPEG·PNG·WebP" })).toBeVisible();

  await page.getByLabel("사진 올리기").setInputFiles({ name: "tiny.png", mimeType: "image/png", buffer: PNG_4X3 });
  await expect(page.getByRole("status").filter({ hasText: "사진을 올렸어요" })).toBeVisible();
  const grid = page.getByRole("list", { name: "사진 목록" });
  await expect(grid.getByRole("img")).toHaveCount(1);
  await expect.poll(() => grid.getByRole("img").evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);

  const aliceContext = await browser.newContext();
  const alice = await aliceContext.newPage();
  await loginInBrowser(alice, "alice");
  await alice.goto(`/clubs/${club}/photos`);
  await expect(alice.getByRole("list", { name: "사진 목록" }).getByRole("img")).toHaveCount(1);
  await aliceContext.close();

  await grid.getByRole("listitem").getByRole("button", { name: "삭제" }).click();
  await expect(page.getByText("아직 사진이 없어요.")).toBeVisible();
});

test("모임 회원이 아니면 사진첩 대신 안내가 보인다", async ({ page }) => {
  const club = await photoClub();
  await loginInBrowser(page, "carol");
  await page.goto(`/clubs/${club}/photos`);
  await expect(page.getByRole("alert").filter({ hasText: "모임 회원만" })).toBeVisible();
  await expect(page.getByLabel("사진 올리기")).toHaveCount(0);
});
