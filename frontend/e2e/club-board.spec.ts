import { expect, test, type Page } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle } from "./support";

// 커뮤니티 5단계 화면: 모임 안 메뉴(공지·게시판·회원), 회원 글쓰기, 만든 사람의 운영자 임명 → 임명된 사람의 모임 공지,
// 회원이 아니면 금지 안내, 생성자 넘기기 뒤 이전 생성자의 모임 탈퇴.
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function approvedClub(name: string) {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name, intro: "내부 게시판 점검", place: "온라인", mode: "ONLINE" })).json();
  await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version });
  await Promise.all([alice.dispose(), operator.dispose()]);
  for (const id of ["bob", "carol"]) {
    const m = await ApiActor.login(id);
    await m.post(`/api/clubs/${created.id}/join`);
    await m.dispose();
  }
  return created.id as number;
}

test("만든 사람이 운영자를 임명하면 그 회원이 모임 공지를 쓰고, 회원은 게시판에 쓴다. 회원이 아니면 금지 안내", async ({ page, browser }) => {
  const club = await approvedClub(uniqueTitle("내부 모임"));

  await loginInBrowser(page, "alice");
  await page.goto(`/clubs/${club}`);
  await page.getByRole("navigation", { name: "모임 안 메뉴" }).getByRole("link", { name: "권한 관리" }).click();
  const bobRow = page.getByTestId("club-member").filter({ hasText: "밥" });
  await expect(bobRow).toContainText("회원");
  await bobRow.getByRole("button", { name: "운영자 임명" }).click();
  await expect(page.getByRole("status").filter({ hasText: "모임 운영자로 임명했어요" })).toBeVisible();
  await expect(bobRow).toContainText("모임 운영자");

  const bobContext = await browser.newContext();
  const bobPage = await bobContext.newPage();
  await loginInBrowser(bobPage, "bob");
  await bobPage.goto(`/clubs/${club}/notices`);
  await bobPage.getByRole("link", { name: "글쓰기" }).click();
  const title = uniqueTitle("모임 공지");
  await visible(bobPage, "제목").fill(title);
  await visible(bobPage, "본문").fill("이번 주는 온라인으로 만나요");
  await bobPage.getByRole("button", { name: "등록" }).click();
  await expect(bobPage.getByRole("heading", { name: title })).toBeVisible();
  await expect(bobPage).toHaveURL(new RegExp(`/clubs/${club}/notices/\\d+$`));
  await bobContext.close();

  const carolContext = await browser.newContext();
  const carolPage = await carolContext.newPage();
  await loginInBrowser(carolPage, "carol");
  await carolPage.goto(`/clubs/${club}/notices`);
  await expect(carolPage.getByRole("link", { name: title })).toBeVisible();
  await expect(carolPage.getByRole("link", { name: "글쓰기" })).toHaveCount(0);
  await carolPage.goto(`/clubs/${club}/board/new`);
  const post = uniqueTitle("회원 글");
  await visible(carolPage, "제목").fill(post);
  await visible(carolPage, "본문").fill("안녕하세요");
  await carolPage.getByRole("button", { name: "등록" }).click();
  await expect(carolPage.getByRole("heading", { name: post })).toBeVisible();
  await carolContext.close();

  const outsider = await browser.newContext();
  const outsiderPage = await outsider.newPage();
  await loginInBrowser(outsiderPage, "operator");
  await outsiderPage.goto(`/clubs/${club}/board`);
  // 서비스 운영자는 읽기만: 글쓰기 버튼이 없다
  await expect(outsiderPage.getByRole("link", { name: post })).toBeVisible();
  await expect(outsiderPage.getByRole("link", { name: "글쓰기" })).toHaveCount(0);
  await outsider.close();
});

test("회원이 아니면 모임 게시판에서 금지 안내를 보고, 생성자를 넘긴 사람은 모임을 탈퇴할 수 있다", async ({ page }) => {
  const club = await approvedClub(uniqueTitle("이전 모임"));

  await loginInBrowser(page, "alice");
  await page.goto(`/clubs/${club}/roles`);
  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("생성자를 넘길까요");
    await dialog.accept();
  });
  await page.getByTestId("club-member").filter({ hasText: "밥" }).getByRole("button", { name: "생성자 넘기기" }).click();
  await expect(page.getByRole("status").filter({ hasText: "생성자를 넘겼어요" })).toBeVisible();
  await expect(page.getByRole("button", { name: "운영자 임명" })).toHaveCount(0);
  await page.goto(`/clubs/${club}`);
  await page.getByRole("button", { name: "모임 탈퇴" }).click();
  await expect(page.getByRole("status").filter({ hasText: "모임에서 탈퇴했어요." })).toBeVisible();
  await page.goto(`/clubs/${club}/board`);
  await expect(appAlert(page).filter({ hasText: "모임 회원만 볼 수 있습니다." })).toBeVisible();
  await expect(page.getByRole("link", { name: "← 모임 소개" })).toBeVisible();
});
