import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle, menuLink } from "./support";

// 재현: A 가 대기 중인 상세를 열었다가 '내 신청' 으로 이동 → B 가 취소해 A 가 승격 → A 가 같은 탭에서 상세로 돌아온다.
// cacheComponents 는 떠난 상세 화면을 상태째 숨겨 두므로, 돌아온 순간 옛 '대기 1번' 이 보일 수 있었다 (공개 배포 실측 0.79초).
const PASSWORD = "return-pass-1!";
const POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);

async function promotedWhileAway(page: Page) {
  const loginId = `rt_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  await ApiActor.signup(loginId, PASSWORD);
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const title = uniqueTitle("복귀");
  const sid = await operator.createSession(title, 1);
  const held = await (await bob.reserve(sid)).json();

  await loginInBrowser(page, loginId, PASSWORD);
  await page.goto(`/sessions/${sid}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("대기 1번");

  await menuLink(page, "내 활동", "내 신청");
  await expect(page.getByRole("heading", { name: "내 신청" })).toBeVisible();
  expect((await bob.cancel(held.id)).status()).toBe(200);
  const item = page.getByRole("listitem").filter({ hasText: title });
  await expect(item.getByTestId("reservation-status")).toHaveText("예약 확정", { timeout: POLL_MS + 5000 });
  await Promise.all([operator.dispose(), bob.dispose()]);
  return { sid, title, item };
}

/** 보이는 '내 예약' 칸에 나타난 글자를 모두 모은다 (숨겨 둔 화면은 제외). */
async function recordVisibleMyReservation(page: Page) {
  await page.evaluate(() => {
    const seen: string[] = ((window as unknown as { __seen: string[] }).__seen = []);
    const capture = () => {
      for (const el of document.querySelectorAll<HTMLElement>('[data-testid="my-reservation"]')) {
        if (el.checkVisibility()) seen.push(el.textContent ?? "");
      }
    };
    new MutationObserver(capture).observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true });
  });
  return () => page.evaluate(() => (window as unknown as { __seen: string[] }).__seen);
}

function detailRequests(page: Page, sid: number) {
  const urls: string[] = [];
  page.on("request", (r) => {
    if (r.method() === "GET" && new URL(r.url()).pathname === `/api/sessions/${sid}`) urls.push(r.url());
  });
  return urls;
}

test("상세로 돌아오면 응답 전에는 '최신 상태 확인 중…' 과 잠긴 버튼, 옛 '대기 1번' 은 보이지 않고 확정이 나타난다", async ({ page }) => {
  const { sid, title, item } = await promotedWhileAway(page);
  await page.route(`**/api/sessions/${sid}`, async (route) => {
    await new Promise((r) => setTimeout(r, 1500));
    await route.continue();
  });
  const seen = await recordVisibleMyReservation(page);
  const requests = detailRequests(page, sid);

  await item.getByRole("link", { name: title }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sid}$`));
  await expect(page.getByRole("status").filter({ hasText: "최신 상태 확인 중…" })).toBeVisible();
  await expect(page.getByTestId("my-reservation")).toHaveText("확인 중…");
  for (const button of await page.locator(".actions button").all()) await expect(button).toBeDisabled();

  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await expect(page.getByRole("status").filter({ hasText: "최신 상태 확인 중…" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "예약 취소" })).toBeEnabled();
  expect(await seen(), "돌아온 뒤 보인 '내 예약'").not.toContain("대기 1번");
  // 복귀 조회는 한 번만 (폴링 간격 안에서 중복 요청·중복 타이머 없음)
  await page.waitForTimeout(1000);
  expect(requests.length, "복귀 뒤 상세 조회 수").toBe(1);
});

test("돌아왔을 때 조회가 실패하면 오류와 '다시 시도' 를 보이고, 옛 상태는 보이지 않으며 버튼은 잠긴다", async ({ page }) => {
  const { sid, title, item } = await promotedWhileAway(page);
  let calls = 0;
  await page.route(`**/api/sessions/${sid}`, async (route) => {
    calls += 1;
    if (calls === 1) {
      await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: "INTERNAL", message: "일시적인 오류" }) });
    } else {
      await route.continue();
    }
  });

  await item.getByRole("link", { name: title }).click();
  const alert = page.getByRole("alert").filter({ hasText: "최신 상태를 확인하지 못했어요" });
  await expect(alert).toBeVisible();
  await expect(page.getByTestId("my-reservation")).not.toHaveText("대기 1번");
  for (const button of await page.locator(".actions button").all()) await expect(button).toBeDisabled();

  await alert.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await expect(alert).toHaveCount(0);
});

test("먼저 보낸 조회가 늦게 도착해도 나중 조회의 최신 상태를 덮어쓰지 않는다", async ({ page }) => {
  const { sid, title, item } = await promotedWhileAway(page);
  let calls = 0;
  await page.route(`**/api/sessions/${sid}`, async (route) => {
    calls += 1;
    if (calls === 1) {
      // 첫 조회: 옛 상태(대기 1번)로 꾸민 응답을 늦게 돌려준다
      const response = await route.fetch();
      const body = await response.json();
      body.myReservation = { ...body.myReservation, status: "WAITING", waitingPosition: 1 };
      await new Promise((r) => setTimeout(r, 2500));
      await route.fulfill({ response, json: body });
    } else {
      await route.continue();
    }
  });
  const seen = await recordVisibleMyReservation(page);

  await item.getByRole("link", { name: title }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sid}$`));
  // 탭 복귀 조회(두 번째 요청)를 바로 일으킨다
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  await page.waitForTimeout(3000); // 늦은 첫 응답이 도착할 때까지
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정");
  expect(await seen()).not.toContain("대기 1번");
  expect(calls).toBeGreaterThanOrEqual(2);
});

test("'내 신청' 으로 돌아와도 응답 전에는 옛 상태 대신 확인 중을 보인다", async ({ page }) => {
  const loginId = `rm_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  await ApiActor.signup(loginId, PASSWORD);
  const operator = await ApiActor.login("operator");
  const bob = await ApiActor.login("bob");
  const title = uniqueTitle("내신청 복귀");
  const sid = await operator.createSession(title, 1);
  const held = await (await bob.reserve(sid)).json();
  await loginInBrowser(page, loginId, PASSWORD);
  await page.goto(`/sessions/${sid}`);
  await page.getByRole("button", { name: "대기 신청" }).click();
  await menuLink(page, "내 활동", "내 신청");
  const item = page.getByRole("listitem").filter({ hasText: title });
  await expect(item.getByTestId("reservation-status")).toHaveText("대기 1번");

  await item.getByRole("link", { name: title }).click();
  expect((await bob.cancel(held.id)).status()).toBe(200);
  await expect(page.getByTestId("my-reservation")).toHaveText("예약 확정", { timeout: POLL_MS + 5000 });

  await page.route("**/api/me/reservations", async (route) => {
    await new Promise((r) => setTimeout(r, 1500));
    await route.continue();
  });
  await menuLink(page, "내 활동", "내 신청");
  await expect(page.getByRole("status").filter({ hasText: "최신 상태 확인 중…" })).toBeVisible();
  await expect(item.getByTestId("reservation-status")).toHaveText("확인 중…");
  await expect(item.getByTestId("reservation-status")).toHaveText("예약 확정");
  await Promise.all([operator.dispose(), bob.dispose()]);
});
