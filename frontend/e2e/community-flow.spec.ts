import { expect, test, type Page } from "@playwright/test";

import { ApiActor, appAlert, loginInBrowser, uniqueTitle, menuLink } from "./support";

// 커뮤니티 6단계: 1~5단계를 잇는 전체 흐름(실제 Spring Boot + PostgreSQL)과 화면 상태·375px·키보드·동작 줄이기.
// 가입 → 회원 게시판 글·댓글·추천·멘션 → 알림함 → 모임 신청·승인 → 가입 → 모임 공지·게시판 → 운영자 임명·회수
// → 생성자 이전 → 모임 탈퇴 → 모임 삭제·계정 탈퇴와 정리.
const PASSWORD = "e2e-flow-pass-1!";
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

const status = (page: Page, text: string | RegExp) => page.getByRole("status").filter({ hasText: text });

async function myId(page: Page): Promise<number> {
  return (await (await page.request.get("/api/auth/me")).json()).id;
}

test("가입부터 게시판·알림·모임 신청·내부 게시판·역할·이전·탈퇴·삭제까지 이어서 정리된다", async ({ page }) => {
  test.setTimeout(120_000);
  const loginId = `e2e_f${Date.now().toString(36)}`;
  const operator = await ApiActor.login("operator");
  const alice = await ApiActor.login("alice");
  try {
    // 1) 가입·로그인
    await page.goto("/signup");
    await page.getByLabel("아이디").fill(loginId);
    await page.getByLabel("비밀번호", { exact: true }).fill(PASSWORD);
    await page.getByLabel("비밀번호 확인").fill(PASSWORD);
    await page.getByRole("button", { name: "가입하기" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.getByRole("textbox", { name: "비밀번호" }).fill(PASSWORD);
    await page.getByRole("button", { name: "로그인" }).click();
    await page.waitForURL((url) => url.pathname !== "/login");
    const me = await myId(page);

    // 2) 회원 게시판 글 → 다른 회원의 멘션 댓글 → 추천
    const title = uniqueTitle("흐름 글");
    await menuLink(page, "게시판", "회원게시판");
    await page.getByRole("link", { name: "글쓰기" }).click();
    await visible(page, "제목").fill(title);
    await visible(page, "본문").fill("첫 글이에요\n잘 부탁해요");
    await page.getByRole("button", { name: "등록" }).click();
    await expect(page.getByRole("heading", { name: title })).toBeVisible();
    const post = Number(new URL(page.url()).pathname.split("/").pop());
    const comment = (await (await alice.post(`/api/posts/${post}/comments`, { body: `@[새 회원#${me}] 환영해요` })).json()).id;
    await page.reload();
    const like = page.getByTestId("comment").filter({ hasText: "환영해요" }).getByRole("button", { name: /^추천/ });
    await like.click();
    await expect(like).toHaveAttribute("aria-pressed", "true");

    // 3) 알림함: 머리글 수 → 열기 → 그 댓글로 이동·초점 → 읽음
    const bell = page.getByRole("banner").getByTestId("notification-bell");
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
    await expect(bell).toHaveAccessibleName("알림, 읽지 않은 알림 1개");
    await bell.click();
    await page.getByTestId("notification").filter({ hasText: title }).getByRole("button", { name: "열기" }).click();
    await expect(page).toHaveURL(new RegExp(`/board/${post}#comment-${comment}$`));
    await expect(page.locator(`#comment-${comment}`)).toBeFocused();
    await expect(bell).toHaveAccessibleName("알림, 읽지 않은 알림 없음");

    // 4) 모임 신청 → 운영자 승인 → 결과 알림
    const clubName = uniqueTitle("흐름 모임");
    await page.goto("/clubs/new");
    await visible(page, "모임 이름").fill(clubName);
    await visible(page, "소개").fill("전체 흐름 확인용 모임");
    await visible(page, "활동 장소").fill("온라인");
    await page.getByRole("radio", { name: "온라인", exact: true }).check();
    await page.getByRole("button", { name: "신청" }).click();
    await expect(page.getByRole("heading", { name: clubName })).toBeVisible();
    const club = Number(new URL(page.url()).pathname.split("/").pop());
    const pending = await (await operator.get(`/api/clubs/${club}`)).json();
    await operator.post(`/api/operator/clubs/${club}/approve`, { version: pending.version });
    await page.goto("/me/notifications");
    await expect(page.getByTestId("notification").filter({ hasText: clubName })).toContainText("모임 신청이 승인됐어요");

    // 5) 다른 회원 가입 → 모임 안 메뉴 → 운영자 임명 → 그 회원의 모임 공지 → 회수 → 거절
    await alice.post(`/api/clubs/${club}/join`);
    await page.goto(`/clubs/${club}`);
    await page.getByRole("navigation", { name: "모임 안 메뉴" }).getByRole("link", { name: "권한 관리" }).click();
    const aliceRow = page.getByTestId("club-member").filter({ hasNotText: "만든 사람" });
    await aliceRow.getByRole("button", { name: "운영자 임명" }).click();
    await expect(status(page, "모임 운영자로 임명했어요")).toBeVisible();
    const noticeTitle = uniqueTitle("모임 공지");
    const notice = await alice.post(`/api/clubs/${club}/boards/notice/posts`, { title: noticeTitle, body: "이번 주 공지" });
    expect(notice.status()).toBe(201);
    const noticeId = (await notice.json()).id;
    await page.goto(`/clubs/${club}/notices`);
    await expect(page.getByRole("link", { name: noticeTitle })).toBeVisible();
    await page.goto(`/clubs/${club}/roles`);
    await aliceRow.getByRole("button", { name: "운영자 회수" }).click();
    await expect(status(page, "권한을 회수했어요")).toBeVisible();
    expect((await alice.post(`/api/clubs/${club}/boards/notice/posts`, { title: "회수 뒤", body: "x" })).status()).toBe(403);

    // 6) 생성자 이전 → 이전 생성자의 모임 탈퇴 → 내부 게시판 권한 없음 안내(다시 시도 없음)
    page.once("dialog", (dialog) => dialog.accept());
    await aliceRow.getByRole("button", { name: "생성자 넘기기" }).click();
    await expect(status(page, "생성자를 넘겼어요")).toBeVisible();
    await page.goto(`/clubs/${club}`);
    await page.getByRole("button", { name: "모임 탈퇴" }).click();
    await expect(status(page, "모임에서 탈퇴했어요.")).toBeVisible();
    await page.goto(`/clubs/${club}/board`);
    await expect(appAlert(page).filter({ hasText: "모임 회원만 볼 수 있습니다." })).toBeVisible();
    await expect(page.getByRole("button", { name: "다시 시도" }).filter({ visible: true })).toHaveCount(0);

    // 7) 새 생성자의 모임 삭제 → 모임 공지·결과 알림까지 정리
    expect((await alice.delete(`/api/clubs/${club}`)).status()).toBe(204);
    expect((await alice.get(`/api/posts/${noticeId}`)).status()).toBe(404);
    await page.goto("/me/notifications");
    await expect(page.getByText(/읽지 않은 알림 \d+개/)).toBeVisible();
    await expect(page.getByTestId("notification").filter({ hasText: clubName })).toHaveCount(0);

    // 8) 계정 탈퇴 → 글은 남고 작성자는 '탈퇴한 회원' → 정리
    await page.goto("/account/withdraw");
    await page.getByRole("textbox", { name: "비밀번호" }).fill(PASSWORD);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/login\?withdrawn=1$/);
    expect((await (await alice.get(`/api/posts/${post}`)).json()).author.label).toBe("탈퇴한 회원");
    expect((await operator.delete(`/api/posts/${post}`)).status()).toBe(204);
  } finally {
    await Promise.all([operator.dispose(), alice.dispose()]);
  }
});

test("화면 상태: 불러오는 중·빈 목록·권한 없음·삭제됨·오류 뒤 다시 시도", async ({ page, browser }) => {
  const alice = await ApiActor.login("alice");
  const operator = await ApiActor.login("operator");
  const created = await (await alice.post("/api/clubs", { name: uniqueTitle("상태 모임"), intro: "상태", place: "온라인", mode: "ONLINE" })).json();
  await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version });
  await operator.dispose();

  await loginInBrowser(page, "alice");
  // 빈 목록
  await page.goto(`/clubs/${created.id}/board`);
  await expect(page.getByText("아직 글이 없어요.").filter({ visible: true })).toBeVisible();
  // 불러오는 중 (응답을 늦춘다)
  await page.route("**/api/clubs/*/boards/notice/posts*", async (route) => {
    await new Promise((r) => setTimeout(r, 800));
    await route.continue();
  });
  await page.goto(`/clubs/${created.id}/notices`);
  await expect(page.getByText("불러오는 중…").filter({ visible: true }).first()).toBeVisible();
  await expect(page.getByText("아직 글이 없어요.").filter({ visible: true })).toBeVisible();
  // 삭제됨
  await page.goto("/board/999999999");
  await expect(appAlert(page).filter({ hasText: "삭제되었거나 찾을 수 없는 글입니다." })).toBeVisible();
  // 오류 뒤 다시 시도 (첫 요청만 실패)
  let failed = false;
  await page.route("**/api/me/notifications", async (route) => {
    if (!failed) {
      failed = true;
      await route.fulfill({ status: 500, contentType: "application/json", body: '{"code":"INTERNAL_ERROR","message":"일시적인 오류예요."}' });
      return;
    }
    await route.continue();
  });
  await page.goto("/me/notifications");
  await page.getByRole("button", { name: "다시 시도" }).filter({ visible: true }).click();
  await expect(page.getByText(/읽지 않은 알림 \d+개/)).toBeVisible();

  // 권한 없음 (회원 아님)
  const other = await browser.newContext();
  const carol = await other.newPage();
  await loginInBrowser(carol, "carol");
  await carol.goto(`/clubs/${created.id}/members`);
  await expect(appAlert(carol).filter({ hasText: "모임 회원만 볼 수 있습니다." })).toBeVisible();
  await other.close();
  await alice.delete(`/api/clubs/${created.id}`);
  await alice.dispose();
});

test.describe("375px·키보드·동작 줄이기", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, reducedMotion: "reduce" });

  test("커뮤니티·모임 화면에 가로 넘침이 없고, 키보드로 알림에 가며, 전환이 거의 즉시다", async ({ page }) => {
    const alice = await ApiActor.login("alice");
    const operator = await ApiActor.login("operator");
    const created = await (
      await alice.post("/api/clubs", { name: uniqueTitle("모바일 모임 이름이 조금 긴 경우"), intro: "긴 소개 ".repeat(40), place: "온라인", mode: "ONLINE" })
    ).json();
    await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version });
    await operator.dispose();
    await alice.post(`/api/clubs/${created.id}/boards/board/posts`, { title: uniqueTitle("모바일 글"), body: "아주긴단어".repeat(60) });

    await loginInBrowser(page, "alice");
    for (const path of ["/board", "/notices", "/me/notifications", "/clubs/promo", "/clubs", `/clubs/${created.id}`,
      `/clubs/${created.id}/board`, `/clubs/${created.id}/members`, "/me/clubs", "/clubs/new"]) {
      await page.goto(path);
      await expect(page.locator("main h1").filter({ visible: true }).first()).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, path).toBeLessThanOrEqual(0);
    }

    // 키보드: 머리글 알림 버튼까지 Tab 으로 가서 초점 테두리가 보이고 Enter 로 알림함
    await page.goto("/clubs");
    await expect(page.getByTestId("club-card").first()).toBeVisible();
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    let reached = false;
    for (let i = 0; i < 60 && !reached; i++) {
      await page.keyboard.press("Tab");
      reached = await page.evaluate(() => document.activeElement?.getAttribute("data-testid") === "notification-bell");
    }
    expect(reached).toBe(true);
    const outline = await page.evaluate(() => getComputedStyle(document.activeElement as Element).outlineStyle);
    expect(outline).not.toBe("none");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/me\/notifications$/);

    // 동작 줄이기: 알림·카드 전환이 거의 즉시
    await page.goto("/clubs");
    await expect(page.getByTestId("club-card").first()).toBeVisible();
    const durations = await page.evaluate(() =>
      [...document.querySelectorAll(".notice, .card")].map((el) => parseFloat(getComputedStyle(el).transitionDuration)),
    );
    expect(durations.length).toBeGreaterThan(0);
    expect(durations.every((d) => d < 0.02)).toBe(true);

    await alice.delete(`/api/clubs/${created.id}`);
    await alice.dispose();
  });
});
