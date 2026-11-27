import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 사진 업로드 개선: HEIC 는 보내기 전에 안내, 프록시 413(본문이 JSON 아님)은 크기 안내 + 다시 시도,
// 보내는 동안 다시 고를 수 없고 취소하면 아무것도 남지 않는다.
const PNG_4X3 = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAIAAAA7ljmRAAAAFUlEQVR4nGPQqDgBRB+2aAARA04OAHDiEzmFaOyDAAAAAElFTkSuQmCC", "base64");

async function myClub() {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("업로드 모임"), intro: "업로드", place: "온라인", mode: "ONLINE" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  await Promise.all([alice.dispose(), operator.dispose()]);
  return created.id as number;
}

async function openAlbum(page: Page, club: number) {
  await loginInBrowser(page, "alice");
  await page.goto(`/clubs/${club}/photos`);
  await expect(page.getByRole("heading", { name: "모임 사진첩" })).toBeVisible();
}

test("HEIC 는 서버로 보내지 않고 지원 형식과 변환 방법을 안내한다", async ({ page }) => {
  const club = await myClub();
  await openAlbum(page, club);
  const posts: string[] = [];
  page.on("request", (r) => r.method() === "POST" && r.url().includes("/photos") && posts.push(r.url()));
  await page.getByLabel("사진 올리기").setInputFiles({ name: "IMG_0001.HEIC", mimeType: "image/heic", buffer: Buffer.from("....ftypheic") });
  await expect(page.getByRole("alert").filter({ hasText: "높은 호환성" })).toBeVisible();
  expect(posts).toHaveLength(0);
});

test("프록시가 413 을 돌려주면 크기 안내와 다시 시도가 보이고, 다시 시도하면 올라간다", async ({ page }) => {
  const club = await myClub();
  await openAlbum(page, club);
  let fail = true;
  await page.route(/\/api\/clubs\/\d+\/photos$/, (route) =>
    route.request().method() === "POST" && fail
      ? route.fulfill({ status: 413, contentType: "text/plain", body: "Request Entity Too Large" })
      : route.continue(),
  );
  await page.getByLabel("사진 올리기").setInputFiles({ name: "tiny.png", mimeType: "image/png", buffer: PNG_4X3 });
  await expect(page.getByRole("alert").filter({ hasText: "4MB" })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByRole("status").filter({ hasText: "사진을 올렸어요" })).toBeVisible();
  await expect(page.getByRole("list", { name: "사진 목록" }).getByRole("img")).toHaveCount(1);
  await expect(page.getByRole("button", { name: "다시 시도" })).toHaveCount(0);
});

test("보내는 동안은 다시 고를 수 없고, 취소하면 사진이 남지 않는다", async ({ page }) => {
  const club = await myClub();
  await openAlbum(page, club);
  let release: () => void = () => {};
  await page.route(/\/api\/clubs\/\d+\/photos$/, async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    await new Promise<void>((r) => (release = r));
    await route.abort();
  });
  await page.getByLabel("사진 올리기").setInputFiles({ name: "tiny.png", mimeType: "image/png", buffer: PNG_4X3 });
  await expect(page.getByLabel("사진 올리기")).toBeDisabled();
  await page.getByRole("button", { name: "올리기 취소" }).click();
  release();
  await expect(page.getByRole("status").filter({ hasText: "올리기를 취소했어요" })).toBeVisible();
  await expect(page.getByLabel("사진 올리기")).toBeEnabled();
  await page.unroute(/\/api\/clubs\/\d+\/photos$/);
  await page.reload();
  await expect(page.getByText("아직 사진이 없어요.")).toBeVisible();
});
