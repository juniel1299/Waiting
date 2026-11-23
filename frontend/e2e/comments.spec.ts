import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 커뮤니티 2단계 화면: 댓글·한 단계 답글(들여쓰기)·추천(선택 여부와 개수)·멘션 자동완성(키보드)·삭제 확인·권한별 노출.
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function postBy(actor: ApiActor, board = "community") {
  const res = await actor.post(`/api/boards/${board}/posts`, { title: uniqueTitle("댓글 글"), body: "본문" });
  return (await res.json()).id as number;
}

test("댓글·답글을 쓰고 추천하고 고치고, 답글이 있는 댓글을 지우면 함께 지운다고 확인한다", async ({ page, browser }) => {
  const alice = await ApiActor.login("alice");
  const id = await postBy(alice);
  await alice.dispose();

  await loginInBrowser(page, "bob");
  await page.goto(`/board/${id}`);
  await visible(page, "댓글").fill("첫 댓글이에요");
  await page.getByRole("button", { name: "댓글 등록" }).click();
  const comment = page.getByTestId("comment").filter({ hasText: "첫 댓글이에요" });
  await expect(comment).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "댓글을 등록했어요" })).toBeVisible();

  const other = await browser.newContext();
  const alicePage = await other.newPage();
  await loginInBrowser(alicePage, "alice");
  await alicePage.goto(`/board/${id}`);
  const aliceComment = alicePage.getByTestId("comment").filter({ hasText: "첫 댓글이에요" });
  await aliceComment.getByRole("button", { name: "답글", exact: true }).click();
  await visible(alicePage, "답글").fill("답글입니다");
  await alicePage.getByRole("button", { name: "답글 등록" }).click();
  const reply = alicePage.getByTestId("reply").filter({ hasText: "답글입니다" });
  await expect(reply).toBeVisible();
  // 답글에는 답글 버튼이 없다 (한 단계)
  await expect(reply.getByRole("button", { name: "답글", exact: true })).toHaveCount(0);
  // 남의 댓글에는 수정·삭제가 없다
  await expect(aliceComment.locator(":scope > .comment-actions").getByRole("button", { name: "수정" })).toHaveCount(0);
  const like = aliceComment.getByRole("button", { name: /^추천/ }).first();
  await expect(like).toHaveAttribute("aria-pressed", "false");
  await like.click();
  await expect(like).toHaveAttribute("aria-pressed", "true");
  await expect(like).toHaveText("추천함 1");
  await like.click();
  await expect(like).toHaveText("추천 0");
  await other.close();

  await page.reload();
  await comment.getByRole("button", { name: "수정" }).first().click();
  await visible(page, "댓글 수정").fill("고친 댓글");
  await page.getByRole("button", { name: "저장" }).click();
  const edited = page.getByTestId("comment").filter({ hasText: "고친 댓글" });
  await expect(edited).toContainText("수정됨");
  await expect(edited.getByTestId("reply")).toHaveCount(1);
  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("답글 1개도 함께 삭제");
    await dialog.accept();
  });
  await edited.getByRole("button", { name: "삭제" }).first().click();
  await expect(page.getByTestId("comment")).toHaveCount(0);
  await expect(page.getByTestId("reply")).toHaveCount(0);
});

test("@ 를 치면 닉네임 후보가 뜨고 키보드로 골라 멘션한다. 본문·댓글의 HTML 은 글자 그대로", async ({ page }) => {
  const nick = `별${Date.now().toString(36).slice(-5)}`;
  const carol = await ApiActor.login("carol");
  expect((await carol.patch("/api/me/profile", { nickname: nick })).status()).toBe(200);
  await carol.dispose();
  const alice = await ApiActor.login("alice");
  const id = await postBy(alice);
  await alice.dispose();

  await loginInBrowser(page, "bob");
  await page.goto(`/board/${id}`);
  const box = visible(page, "댓글");
  await box.click();
  await page.keyboard.type(`<b>굵게</b> 안녕 @${nick.slice(0, 4)}`);
  const list = page.getByRole("listbox", { name: "멘션할 회원" });
  await expect(list.getByRole("option").filter({ hasText: nick })).toBeVisible();
  await expect(box).toHaveAttribute("aria-expanded", "true");
  await page.keyboard.press("Enter");
  await expect(box).toHaveValue(new RegExp(`@\\[${nick}#\\d+\\] $`));
  await page.getByRole("button", { name: "댓글 등록" }).click();
  const comment = page.getByTestId("comment").last();
  await expect(comment.locator(".mention")).toHaveText(`@${nick}`);
  await expect(comment.locator(".comment-body")).toContainText("<b>굵게</b>");
  expect(await comment.locator(".comment-body b").count()).toBe(0);
});

test("비회원은 공지 댓글을 읽기만, 데모는 회원 게시판 댓글을 읽기만 한다", async ({ page, browser }) => {
  const operator = await ApiActor.login("operator");
  const notice = await postBy(operator, "notice");
  await operator.dispose();
  const bob = await ApiActor.login("bob");
  await bob.post(`/api/posts/${notice}/comments`, { body: "공지에 단 댓글" });
  const post = await postBy(bob);
  await bob.post(`/api/posts/${post}/comments`, { body: "게시판 댓글" });
  await bob.dispose();

  const anon = await (await browser.newContext()).newPage();
  await anon.goto(`/notices/${notice}`);
  await expect(anon.getByTestId("comment").filter({ hasText: "공지에 단 댓글" })).toBeVisible();
  await expect(anon.getByRole("button", { name: "댓글 등록" })).toHaveCount(0);
  await expect(anon.getByText("한 회원만 댓글을 쓸 수 있어요")).toBeVisible();
  await expect(anon.getByRole("button", { name: /^추천/ })).toBeDisabled();
  await anon.context().close();

  await page.goto("/");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await page.goto(`/board/${post}`);
  await expect(page.getByTestId("comment").filter({ hasText: "게시판 댓글" })).toBeVisible();
  await expect(page.getByText("체험 중에는 댓글을 읽기만 할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: /^추천/ })).toBeDisabled();
  await expect(page.getByRole("button", { name: "답글", exact: true })).toHaveCount(0);
});

test("내 정보에서 공개 닉네임을 바꾸면 작성자 표시가 바뀐다", async ({ page }) => {
  const nick = `닉${Date.now().toString(36).slice(-5)}`;
  await loginInBrowser(page, "alice");
  await page.getByRole("link", { name: "내 정보" }).click();
  await visible(page, "공개 닉네임").fill("운영자");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "쓸 수 없어요" })).toBeVisible();
  await visible(page, "공개 닉네임").fill(nick);
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("status").filter({ hasText: nick })).toBeVisible();
  await page.goto("/board/new");
  await visible(page, "제목").fill(uniqueTitle("닉네임 글"));
  await visible(page, "본문").fill("본문");
  await page.getByRole("button", { name: "등록" }).click();
  await expect(page.getByTestId("post-meta")).toContainText(nick);
});
