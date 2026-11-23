import { expect, request, test, type Page } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle } from "./support";

// 커뮤니티 1단계 화면: 회원 게시판·서비스 공지의 목록·상세·작성·수정, 권한별 버튼, 검색 조건 주소 유지, 본문 텍스트 표시(XSS 없음),
// 비회원 공지 열람, 데모 조회만, 삭제된 글 안내.
const baseURL = `http://localhost:${process.env.E2E_PORT ?? 3000}`;
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

test("회원이 글을 쓰고 고치고 지운다. 다른 회원에게는 수정·삭제 버튼이 없다", async ({ page, browser }) => {
  const title = uniqueTitle("게시판");
  await loginInBrowser(page, "alice");
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "게시판" }).click();
  await expect(page).toHaveURL(/\/board$/);
  await page.getByRole("link", { name: "글쓰기" }).click();
  await visible(page, "제목").fill(title);
  await visible(page, "본문").fill("첫 줄\n둘째 줄");
  await page.getByRole("button", { name: "등록" }).click();
  await expect(page).toHaveURL(/\/board\/\d+$/);
  const url = page.url();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  const body = page.getByTestId("post-body");
  await expect(body).toHaveText("첫 줄\n둘째 줄");
  expect(await body.evaluate((el) => getComputedStyle(el).whiteSpace)).toBe("pre-wrap");
  // 작성자는 공개 닉네임(기본 회원{id})으로만 보이고 로그인 아이디는 보이지 않는다
  await expect(page.getByTestId("post-meta")).not.toContainText("alice");
  await expect(page.getByTestId("post-meta")).not.toContainText("수정됨");

  await page.getByRole("link", { name: "수정" }).click();
  await expect(page).toHaveURL(/\/board\/\d+\/edit$/);
  await visible(page, "제목").fill(`${title} 고침`);
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page).toHaveURL(url);
  await expect(page.getByRole("heading", { name: `${title} 고침` })).toBeVisible();
  await expect(page.getByTestId("post-meta")).toContainText("수정됨");

  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await loginInBrowser(otherPage, "bob");
  await otherPage.goto(url);
  await expect(otherPage.getByRole("heading", { name: `${title} 고침` })).toBeVisible();
  await expect(otherPage.getByRole("link", { name: "수정" })).toHaveCount(0);
  await expect(otherPage.getByRole("button", { name: "삭제" })).toHaveCount(0);

  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("복구할 수 없어요");
    await dialog.accept();
  });
  await page.getByRole("button", { name: "삭제" }).click();
  await expect(page).toHaveURL(/\/board$/);
  await otherPage.reload();
  await expect(appAlert(otherPage)).toContainText("삭제되었거나 찾을 수 없는 글입니다");
  await expect(otherPage.getByRole("link", { name: "게시판 목록으로" })).toBeVisible();
  await other.close();
});

test("본문과 제목의 HTML·스크립트는 글자 그대로 보이고 실행되지 않는다", async ({ page }) => {
  const alice = await ApiActor.login("alice");
  const payload = `<img src=x onerror="window.__xss=1"><script>window.__xss=2</script>`;
  const res = await alice.post("/api/boards/community/posts", { title: `XSS ${payload}`, body: payload });
  const id = (await res.json()).id;
  await alice.dispose();

  await loginInBrowser(page, "bob");
  await page.goto(`/board/${id}`);
  await expect(page.getByTestId("post-body")).toHaveText(payload);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(`XSS ${payload}`);
  expect(await page.getByTestId("post-body").locator("img, script").count()).toBe(0);
  expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
});

test("검색·쪽 조건은 주소에 남고 상세에 갔다 와도 유지된다", async ({ page }) => {
  const token = uniqueTitle("쪽");
  const alice = await ApiActor.login("alice");
  for (let i = 1; i <= 21; i++) {
    await alice.post("/api/boards/community/posts", { title: `${token} ${String(i).padStart(2, "0")}`, body: "본문" });
  }
  await alice.dispose();

  await loginInBrowser(page, "bob");
  await page.goto("/board");
  await visible(page, "검색어").fill(token);
  await visible(page, "검색어").press("Enter");
  await expect(page.locator(".result-count:visible")).toHaveText("21개 글");
  await page.getByRole("navigation", { name: "쪽 이동" }).getByRole("link", { name: "다음" }).click();
  await expect(page).toHaveURL(/page=2/);
  const rows = page.locator(".post-list:visible .post-title");
  await expect(rows).toHaveText([`${token} 01`]);
  const listUrl = page.url();
  await rows.first().click();
  await page.getByRole("link", { name: "← 게시판 목록" }).click();
  await expect(page).toHaveURL(listUrl);
  await expect(rows).toHaveText([`${token} 01`]);
});

test("비회원은 공지를 읽을 수 있고 회원 게시판은 로그인으로 보낸다. 공지 쓰기는 운영자만", async ({ page, browser }) => {
  const title = uniqueTitle("공지");
  const operator = await ApiActor.login("operator");
  const id = (await (await operator.post("/api/boards/notice/posts", { title, body: "모두 보세요" })).json()).id;
  await operator.dispose();

  const anon = await (await browser.newContext()).newPage();
  await anon.goto("/notices");
  await expect(anon.getByRole("heading", { name: "공지사항" })).toBeVisible();
  await anon.getByRole("link", { name: title }).click();
  await expect(anon).toHaveURL(new RegExp(`/notices/${id}$`));
  await expect(anon.getByTestId("post-body")).toHaveText("모두 보세요");
  await expect(anon.getByRole("banner").getByRole("link", { name: "로그인" })).toBeVisible();
  await expect(anon.getByRole("link", { name: "글쓰기" })).toHaveCount(0);
  await anon.goto("/board");
  await expect(anon).toHaveURL(/\/login$/);
  await anon.context().close();

  await loginInBrowser(page, "alice");
  await page.goto("/notices");
  await expect(page.getByRole("heading", { name: "공지사항" })).toBeVisible();
  await expect(page.getByRole("link", { name: "글쓰기" })).toHaveCount(0);
  await page.goto("/notices/new");
  await expect(appAlert(page)).toContainText("권한이 없");

  const op = await (await browser.newContext()).newPage();
  await loginInBrowser(op, "operator");
  await op.goto("/notices");
  await op.getByRole("link", { name: "글쓰기" }).click();
  await visible(op, "제목").fill(`${title} 2`);
  await visible(op, "본문").fill("운영자 공지");
  await op.getByRole("button", { name: "등록" }).click();
  await expect(op).toHaveURL(/\/notices\/\d+$/);
  await expect(op.getByTestId("post-meta")).toContainText("운영자");
  await op.context().close();
});

test("데모 방문자는 회원 게시판을 읽기만 한다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "게시판" }).click();
  await expect(page.getByRole("heading", { name: "회원 게시판" })).toBeVisible();
  await expect(page.getByRole("link", { name: "글쓰기" })).toHaveCount(0);
  await expect(page.getByText("체험 중에는 글을 읽기만 할 수 있어요.")).toBeVisible();
  await page.goto("/board/new");
  await expect(appAlert(page)).toContainText("권한이 없");
  // 서버도 막는다
  const ctx = await request.newContext({ baseURL, storageState: await page.context().storageState() });
  const csrf = await (await ctx.get("/api/auth/csrf")).json();
  const res = await ctx.post("/api/boards/community/posts", { data: { title: "x", body: "y" }, headers: { [csrf.headerName]: csrf.token } });
  expect(res.status()).toBe(403);
  await ctx.dispose();
});

test.describe("모바일·키보드", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test("좁은 화면에서 가로 스크롤 없이 목록이 보이고, 키보드로 글을 쓴다", async ({ page }) => {
    const title = uniqueTitle("키보드 글");
    await loginInBrowser(page, "alice");
    await page.goto("/board");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.getByRole("link", { name: "글쓰기" }).focus();
    await page.keyboard.press("Enter");
    await visible(page, "제목").focus();
    await page.keyboard.type(title);
    await page.keyboard.press("Tab");
    await page.keyboard.type("키보드로 쓴 본문");
    await page.getByRole("button", { name: "등록" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: title })).toBeVisible();
  });
});
