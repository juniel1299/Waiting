import { expect, request, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 3단계 운영자 화면: 계정 검색 → 영향 미리보기 → 사유 입력 → 이용 정지(대상의 다음 요청 차단) → 해제 → 강제탈퇴,
// 사용량 대시보드 수치 변화, 키보드 조작, 모바일 배치.
const PASSWORD = "moderate-e2e-1!";
const baseURL = `http://localhost:${process.env.E2E_PORT ?? 3000}`;

// cacheComponents 는 떠난 화면을 숨겨 둔 채 DOM 에 남기므로, 보이는 요소만 본다
const visibleLabel = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });

async function loginStatus(loginId: string): Promise<{ status: number; code?: string }> {
  const ctx = await request.newContext({ baseURL });
  const csrf = await (await ctx.get("/api/auth/csrf")).json();
  const res = await ctx.post("/api/auth/login", {
    data: { loginId, password: PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  const body = await res.json().catch(() => ({}));
  await ctx.dispose();
  return { status: res.status(), code: body.code };
}

async function openAccount(page: Page, loginId: string) {
  await page.goto("/operator/accounts");
  await visibleLabel(page, "아이디·이름").fill(loginId);
  await page.getByRole("button", { name: "계정 검색" }).click();
  await expect(page.getByText("1개 계정")).toBeVisible();
  await page.getByRole("button", { name: `${loginId} 조치` }).click();
  return page.getByTestId("impact");
}

test("운영자가 계정을 찾아 영향을 보고, 사유와 함께 정지·해제·강제탈퇴한다. 정지·탈퇴된 사용자는 다음 요청부터 막힌다", async ({ page }) => {
  const loginId = `mo_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  const reason = `e2e 강제탈퇴 사유 ${Math.random().toString(36).slice(2, 8)}`;
  const target = await ApiActor.signup(loginId, PASSWORD);
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const full = await operator.createSession(uniqueTitle("강제탈퇴 대상 확정"), 1);
  expect((await target.reserve(full)).status()).toBe(201);
  expect((await bob.waitlist(full)).status()).toBe(201);
  const own = await target.post("/api/sessions", {
    title: uniqueTitle("대상이 만든 회차"),
    startsAt: new Date(Date.now() + 72 * 3600 * 1000).toISOString(),
    location: "판교",
    capacity: 3,
  });
  expect(own.status()).toBe(201);

  await loginInBrowser(page, "operator");
  const impact = await openAccount(page, loginId);
  await expect(page.getByRole("heading", { name: `${loginId} 조치` })).toBeFocused();
  await expect(impact).toContainText("1건 (강제탈퇴 시 취소, 대기자 1명 자동 확정)");
  await expect(impact).toContainText("1개와 그 신청 0건");

  // 사유 없이는 조치하지 않는다
  await page.getByRole("button", { name: "이용 정지" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "조치 사유를 입력해 주세요" })).toBeVisible();
  await page.getByLabel("조치 사유 (필수, 500자 이내)").fill("e2e 정지 사유");
  await page.getByRole("button", { name: "이용 정지" }).click();
  await expect(page.getByRole("status").filter({ hasText: `${loginId} 계정을 이용 정지했어요` })).toBeVisible();
  await expect(impact).toContainText("정지");

  // 이미 로그인해 있던 대상의 다음 요청은 401, 다시 로그인하면 정지 안내
  expect((await target.get("/api/me/reservations")).status()).toBe(401);
  expect(await loginStatus(loginId)).toEqual({ status: 403, code: "ACCOUNT_SUSPENDED" });

  await page.getByLabel("조치 사유 (필수, 500자 이내)").fill("e2e 해제 사유");
  await page.getByRole("button", { name: "정지 해제" }).click();
  await expect(page.getByRole("status").filter({ hasText: "정지 해제했어요" })).toBeVisible();
  expect((await loginStatus(loginId)).status).toBe(200);

  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("대기자 1명 확정");
    expect(dialog.message()).toContain("되돌릴 수 없어요");
    await dialog.accept();
  });
  await page.getByLabel("조치 사유 (필수, 500자 이내)").fill(reason);
  await page.getByRole("button", { name: "강제탈퇴" }).click();
  await expect(page.getByRole("status").filter({ hasText: "강제탈퇴했어요" })).toBeVisible();
  expect((await loginStatus(loginId)).status).toBe(401);
  const bobMine = await (await bob.get("/api/me/reservations")).json();
  expect(bobMine.find((r: { sessionId: number }) => r.sessionId === full).status).toBe("CONFIRMED");

  // 조치 기록: 처리자·조치·사유, 탈퇴한 대상은 익명 표시
  const log = page.getByRole("region", { name: "최근 조치 기록" });
  const row = log.getByRole("row").filter({ hasText: reason });
  await expect(row).toContainText("강제탈퇴");
  await expect(row).toContainText("탈퇴한 회원 #");
  await expect(row).toContainText("operator");
  await expect(row).toContainText("신청 1건 취소 · 회차 1개 삭제");
  await expect(log.getByRole("row").filter({ hasText: loginId })).toHaveCount(0);
  await Promise.all([target.dispose(), operator.dispose(), bob.dispose()]);
});

test("사용량 대시보드: 회차를 만들면 현재 데이터와 누적 회차 생성이 함께 늘고, 지우면 현재 데이터만 줄어든다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  await loginInBrowser(page, "operator");
  await expect(page.getByRole("heading", { name: "운영자 · 사용량" })).toBeVisible();
  const sessions = page.getByTestId("current-regular-sessions");
  const created = page.getByTestId("total-regular-SESSION_CREATED");
  await expect(page.getByText("집계 시작:")).toBeVisible();
  const before = { sessions: Number(await sessions.textContent()), created: Number(await created.textContent()) };

  const id = await operator.createSession(uniqueTitle("대시보드"), 2);
  await page.reload();
  await expect(sessions).toHaveText(String(before.sessions + 1));
  await expect(created).toHaveText(String(before.created + 1));

  expect((await operator.delete(`/api/sessions/${id}`)).status()).toBe(204);
  await page.reload();
  await expect(sessions).toHaveText(String(before.sessions));
  await expect(created).toHaveText(String(before.created + 1));
  await operator.dispose();
});

test("키보드만으로 계정을 찾고 조치 영역을 연다. 운영자 본인에게는 조치 버튼이 없다", async ({ page }) => {
  await loginInBrowser(page, "operator");
  await page.goto("/operator/accounts");
  await visibleLabel(page, "아이디·이름").focus();
  await page.keyboard.type("operator");
  await page.keyboard.press("Enter");
  const toggle = page.getByRole("button", { name: "operator 조치", exact: true });
  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "operator 조치" })).toBeFocused();
  await expect(page.getByRole("button", { name: "조치 닫기" })).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText("본인 계정에는 조치할 수 없어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "이용 정지" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "강제탈퇴" })).toHaveCount(0);
});

test.describe("모바일", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test("운영자 화면은 좁은 화면에서 가로 스크롤 없이 보이고, 넓은 표는 표 안에서만 넘긴다", async ({ page }) => {
    await loginInBrowser(page, "operator");
    for (const path of ["/operator", "/operator/accounts", "/operator/sessions"]) {
      await page.goto(path);
      await expect(page.getByRole("navigation", { name: "운영자 메뉴" })).toBeVisible();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, path).toBeLessThanOrEqual(0);
    }
  });
});
