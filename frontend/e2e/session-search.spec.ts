import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 2단계 회차 검색 화면: 주소가 조건의 원본(상세 왕복에도 유지), 서버 페이지네이션, 키보드 조작, 모바일 배치,
// 내가 만든 회차(생성자 링크)와 수정 전용 화면.
const HOUR = 3600 * 1000;

let operator: ApiActor;

test.beforeAll(async () => {
  operator = await ApiActor.login("operator");
});

test.afterAll(async () => {
  await operator.dispose();
});

/** 같은 검색어를 가진 회차 n 개 (1시간 간격, 시작 임박순 = 번호순). */
async function createSeries(token: string, n: number): Promise<number[]> {
  const ids: number[] = [];
  for (let i = 1; i <= n; i++) {
    ids.push(await operator.createSession(`${token} ${String(i).padStart(2, "0")}`, 3, new Date(Date.now() + (24 + i) * HOUR)));
  }
  return ids;
}

// cacheComponents 는 떠난 화면을 숨겨 둔 채 DOM 에 남기므로, 보이는 목록만 본다
const titles = (page: Page) => page.locator(".cards .card .title:visible");
const label = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });
const count = (page: Page, n: number) => page.locator(".result-count:visible").filter({ hasText: new RegExp(`^${n}개 회차$`) });

test("검색 조건은 주소에 남고, 상세에 갔다가 '← 회차 목록'·뒤로 가기로 돌아와도 같은 조건·쪽이 유지된다", async ({ page }) => {
  const token = uniqueTitle("왕복");
  await createSeries(token, 12);
  await loginInBrowser(page, "alice");

  await label(page, "검색어").fill(token);
  await label(page, "검색 범위").selectOption("TITLE");
  await page.getByRole("button", { name: "검색" }).click();
  await expect(count(page, 12)).toBeVisible();
  await label(page, "정렬").selectOption("LATE");
  await expect(titles(page).first()).toHaveText(`${token} 12`);
  await page.getByRole("navigation", { name: "쪽 이동" }).getByRole("link", { name: "2쪽" }).click();
  await expect(page).toHaveURL(/page=2/);
  await expect(titles(page)).toHaveText([`${token} 02`, `${token} 01`]);
  const listUrl = page.url();
  expect(new URL(listUrl).searchParams.get("q")).toBe(token);
  expect(new URL(listUrl).searchParams.get("sort")).toBe("LATE");

  await titles(page).first().click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await page.getByRole("link", { name: "← 회차 목록" }).click();
  await expect(page).toHaveURL(listUrl);
  await expect(label(page, "검색어")).toHaveValue(token);
  await expect(label(page, "정렬")).toHaveValue("LATE");
  await expect(page.getByRole("navigation", { name: "쪽 이동" }).locator('[aria-current="page"]')).toHaveText("2");
  await expect(titles(page)).toHaveText([`${token} 02`, `${token} 01`]);

  await titles(page).last().click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await page.goBack();
  await expect(page).toHaveURL(listUrl);
  await expect(titles(page)).toHaveText([`${token} 02`, `${token} 01`]);

  // 새로고침해도 주소의 조건 그대로
  await page.reload();
  await expect(count(page, 12)).toBeVisible();
  await expect(titles(page)).toHaveText([`${token} 02`, `${token} 01`]);
});

test("서버 페이지네이션: 10개씩, 조건을 바꾸면 1쪽으로, 결과 없음과 초기화", async ({ page }) => {
  const token = uniqueTitle("쪽");
  await createSeries(token, 12);
  await loginInBrowser(page, "alice");
  await page.goto(`/sessions?q=${encodeURIComponent(token)}`);

  await expect(count(page, 12)).toBeVisible();
  await expect(titles(page)).toHaveCount(10);
  await expect(titles(page).first()).toHaveText(`${token} 01`);
  const nav = page.getByRole("navigation", { name: "쪽 이동" });
  await nav.getByRole("link", { name: "다음" }).click();
  await expect(titles(page)).toHaveText([`${token} 11`, `${token} 12`]);
  await expect(nav.getByText("다음")).toHaveAttribute("aria-disabled", "true");

  await label(page, "정렬").selectOption("TITLE");
  await expect(page).not.toHaveURL(/page=/);
  await expect(titles(page)).toHaveCount(10);

  await label(page, "모집 상태").selectOption("ENDED");
  await expect(count(page, 0)).toBeVisible();
  await expect(page.getByText("조건에 맞는 회차가 없어요.")).toBeVisible();
  await page.getByRole("button", { name: "조건 초기화" }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(label(page, "검색어")).toHaveValue("");
  await expect(label(page, "모집 상태")).toHaveValue("UPCOMING");
});

test("키보드만으로 검색하고 쪽을 옮긴다", async ({ page }) => {
  const token = uniqueTitle("키보드");
  await createSeries(token, 11);
  await loginInBrowser(page, "alice");

  await label(page, "검색어").focus();
  await page.keyboard.type(token);
  await page.keyboard.press("Enter");
  await expect(count(page, 11)).toBeVisible();
  expect(new URL(page.url()).searchParams.get("q")).toBe(token);

  const next = page.getByRole("navigation", { name: "쪽 이동" }).getByRole("link", { name: "다음" });
  await next.focus();
  await expect(next).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(titles(page)).toHaveText([`${token} 11`]);

  // 검색 칸 사이를 Tab 으로 오가고, 고르는 칸은 바꾸자마자 적용 (1쪽부터)
  await label(page, "검색어").focus();
  await page.keyboard.press("Shift+Tab");
  await expect(label(page, "검색 범위")).toBeFocused();
  await label(page, "정렬").focus();
  await label(page, "정렬").selectOption("LATE");
  await expect(page).toHaveURL(/sort=LATE/);
  await expect(page).not.toHaveURL(/page=/);
  await expect(titles(page).first()).toHaveText(`${token} 11`);
});

test.describe("모바일", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test("좁은 화면에서도 가로 스크롤 없이 검색 칸과 카드(제목·시작·장소·확정/정원·상태 순)가 보인다", async ({ page }) => {
    const token = uniqueTitle("모바일");
    await createSeries(token, 2);
    await loginInBrowser(page, "alice");
    await page.goto(`/sessions?q=${encodeURIComponent(token)}`);
    await expect(count(page, 2)).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    for (const name of ["검색 범위", "검색어", "시작일부터", "시작일까지", "모집 상태", "정렬"]) {
      const box = await label(page, name).boundingBox();
      expect(box, name).not.toBeNull();
      expect(box!.x + box!.width, `${name} 오른쪽 끝`).toBeLessThanOrEqual(375);
    }
    const card = page.locator(".cards .card:visible").first();
    await expect(card.locator("dt")).toHaveText(["시작", "장소", "확정", "상태"]);
    await expect(card).toContainText("0 / 정원 3");
    await expect(card).toContainText("예약 가능");
  });
});

test("내가 만든 회차: 내 회차만 보이고, 수정 전용 화면에서 고치면 상세에 반영된다. 다른 회원은 수정할 수 없다", async ({ page, browser }) => {
  // 가입 한도(같은 IP)를 다른 가입 테스트와 나눠 쓰지 않도록 시드 회원 bob 을 생성자로 쓴다
  const owner = await ApiActor.login("bob");
  const title = uniqueTitle("내 회차");
  const res = await owner.post("/api/sessions", {
    title,
    startsAt: new Date(Date.now() + 48 * HOUR).toISOString(),
    location: "판교",
    capacity: 2,
  });
  expect(res.status()).toBe(201);
  const sessionId = (await res.json()).id;
  await operator.createSession(`${title} 남의 것`, 2);

  await loginInBrowser(page, "bob");
  await page.getByRole("link", { name: "내가 만든 회차" }).click();
  await expect(page).toHaveURL(/\/me\/sessions$/);
  // 같은 검색어의 운영자 회차('… 남의 것')는 내가 만든 회차에 나오지 않는다
  await label(page, "검색어").fill(title);
  await label(page, "검색어").press("Enter");
  await expect(count(page, 1)).toBeVisible();
  await expect(titles(page)).toHaveText([title]);

  await page.getByRole("link", { name: `${title} 수정` }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}/edit$`));
  await expect(label(page, "제목")).toHaveValue(title);
  await expect(label(page, "정원")).toHaveCount(0);
  await label(page, "제목").fill(`${title} 수정됨`);
  await label(page, "장소").fill("강남");
  await page.getByRole("button", { name: "수정 저장" }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}$`));
  await expect(page.getByRole("heading", { name: `${title} 수정됨` })).toBeVisible();
  await expect(page.locator(".facts")).toContainText("강남");
  // 내가 만든 회차에서 왔으니 목록 링크도 그쪽으로
  await page.getByRole("link", { name: "← 내가 만든 회차" }).click();
  await expect(page).toHaveURL(/\/me\/sessions\?q=/);

  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await loginInBrowser(otherPage, "alice");
  await otherPage.goto(`/sessions/${sessionId}/edit`);
  await expect(otherPage.getByRole("alert").filter({ hasText: "수정할 권한이 없어요" })).toBeVisible();
  await expect(otherPage.getByRole("button", { name: "수정 저장" })).toHaveCount(0);
  await other.close();
  await owner.dispose();
});
