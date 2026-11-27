import { expect, test, type Locator, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 화면 정리(2026-11-27): 날짜 입력이 칸 안에, 같은 줄 입력·버튼 높이·아래선, 라디오 가로 배치·키보드,
// 공개 프로필·모임 상세·회차 관리 영역 구분과 좁은 화면 가로 넘침 없음.
const WIDTHS = [1280, 390, 375];

const box = async (l: Locator) => {
  const b = await l.boundingBox();
  if (!b) throw new Error("보이지 않는 요소");
  return b;
};

async function noHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
}

/** 같은 줄의 요소들이 높이와 아래선이 같다(1px 오차). */
async function sameRow(items: Locator[]) {
  const boxes = await Promise.all(items.map(box));
  const heights = boxes.map((b) => Math.round(b.height));
  const bottoms = boxes.map((b) => Math.round(b.y + b.height));
  expect(Math.max(...heights) - Math.min(...heights), `높이 ${heights}`).toBeLessThanOrEqual(1);
  expect(Math.max(...bottoms) - Math.min(...bottoms), `아래선 ${bottoms}`).toBeLessThanOrEqual(1);
}

for (const width of WIDTHS) {
  test(`${width}px: 날짜 입력은 칸과 검색 상자 안에 있고, 좁은 화면에서는 한 줄에 하나씩 놓인다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginInBrowser(page, "operator");
    for (const [path, from, to] of [
      ["/operator/sessions?from=2026-11-01&to=2026-12-31", "시작일부터", "시작일까지"],
      ["/operator", "집계 시작일", "집계 종료일"],
    ] as const) {
      await page.goto(path);
      const start = page.getByLabel(from, { exact: true }).filter({ visible: true });
      const end = page.getByLabel(to, { exact: true }).filter({ visible: true });
      await expect(start).toBeVisible();
      const container = await box(start.locator("xpath=ancestor::form[1]"));
      for (const input of [start, end]) {
        const b = await box(input);
        expect(b.x, `${path} ${from} 왼쪽`).toBeGreaterThanOrEqual(container.x);
        expect(b.x + b.width, `${path} 날짜 입력 오른쪽이 상자 안`).toBeLessThanOrEqual(container.x + container.width);
        // 값이 잘리지 않는다: 입력 안 글자 영역이 넘치지 않음
        expect(await input.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(0);
      }
      const [a, b] = [await box(start), await box(end)];
      if (width < 600) expect(b.y, "좁은 화면: 종료일은 시작일 아래 줄").toBeGreaterThan(a.y + a.height - 1);
      else expect(Math.round(b.y), "넓은 화면: 같은 줄").toBe(Math.round(a.y));
      await noHorizontalOverflow(page);
    }
    // 키보드로 날짜 입력에 들어가면 포커스 표시(링)가 보인다
    await page.goto("/operator/sessions");
    const start = page.getByLabel("시작일부터", { exact: true }).filter({ visible: true });
    await start.focus();
    expect(await start.evaluate((el) => getComputedStyle(el).outlineStyle !== "none" || getComputedStyle(el).boxShadow !== "none")).toBe(true);
  });
}

test("버튼·링크 버튼·메뉴·검색 입력은 같은 높이(44px)이고, 같은 줄이면 아래선이 맞는다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  await page.goto("/board");
  const main = page.getByRole("main");
  const write = main.getByRole("link", { name: "글쓰기" });
  expect(Math.round((await box(write)).height)).toBe(44);
  // 링크 버튼 글자는 세로 가운데
  expect(await write.evaluate((el) => getComputedStyle(el).alignItems)).toBe("center");
  await sameRow([main.getByRole("button", { name: "검색", exact: true }), main.getByRole("button", { name: "초기화", exact: true })]);

  if ((page.viewportSize()?.width ?? 1280) >= 900) {
    const side = page.getByRole("complementary", { name: "사이드 메뉴" });
    expect(Math.round((await box(side.getByRole("button", { name: "게시판", exact: true }))).height)).toBe(44);
    expect(Math.round((await box(side.getByRole("link", { name: "회원게시판" }))).height)).toBe(44);
  } else {
    // 모바일: 메뉴 버튼과 서랍 안 항목
    const menu = page.getByRole("button", { name: "메뉴", exact: true });
    expect(Math.round((await box(menu)).height)).toBe(44);
    await menu.click();
    const drawer = page.getByRole("dialog", { name: "메뉴" });
    expect(Math.round((await box(drawer.getByRole("button", { name: "게시판", exact: true }))).height)).toBe(44);
    await page.keyboard.press("Escape");
  }

  await page.goto("/clubs");
  await sameRow([main.getByRole("button", { name: "검색", exact: true }), main.getByRole("button", { name: "초기화", exact: true })]);
  expect(Math.round((await box(main.getByRole("link", { name: "모임 만들기 신청" }))).height)).toBe(44);

  await page.goto("/sessions");
  // 넓은 화면은 날짜·선택창이 한 줄, 좁은 화면은 날짜가 각자 한 줄이고 선택창 둘이 한 줄
  const wide = (page.viewportSize()?.width ?? 1280) >= 600;
  await sameRow([...(wide ? [main.getByLabel("시작일부터")] : []), main.getByLabel("모집 상태"), main.getByLabel("정렬")]);
  // 선택창도 입력과 같은 44px (Safari 는 기본 모양 select 에 최소 높이를 적용하지 않았다)
  expect(Math.round((await box(main.getByLabel("정렬"))).height)).toBe(44);
});

test("모임 검색어는 주소에 남고, 초기화하면 검색어와 주소가 함께 비워진다", async ({ page }) => {
  await loginInBrowser(page, "alice");
  await page.goto("/clubs");
  const main = page.getByRole("main");
  await expect(main.getByRole("button", { name: "초기화", exact: true })).toBeDisabled();
  await main.getByLabel("모임 이름·소개 검색").fill("없는모임검색어");
  await main.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page).toHaveURL(/q=/);
  await page.reload();
  await expect(main.getByLabel("모임 이름·소개 검색")).toHaveValue("없는모임검색어");
  await main.getByRole("button", { name: "초기화", exact: true }).click();
  await expect(page).toHaveURL(/\/clubs$/);
  await expect(main.getByLabel("모임 이름·소개 검색")).toHaveValue("");
});

for (const width of [1280, 375]) {
  test(`${width}px: 모임 만들기 신청의 활동 방식은 가로로 놓이고, 라벨 클릭·방향키로 고른다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginInBrowser(page, "alice");
    await page.goto("/clubs/new");
    const group = page.getByRole("group", { name: "활동 방식" });
    const radios = group.getByRole("radio");
    await expect(radios).toHaveCount(3);
    const first = await box(radios.nth(0));
    const second = await box(radios.nth(1));
    expect(Math.round(second.y), "두 선택지가 같은 줄").toBe(Math.round(first.y));
    await group.getByText("온라인", { exact: true }).click();
    await expect(group.getByRole("radio", { name: "온라인", exact: true })).toBeChecked();
    // Safari 는 라벨을 눌러도 라디오에 포커스를 주지 않으므로 키보드 확인은 포커스를 옮긴 뒤
    await radios.nth(1).focus();
    await page.keyboard.press("ArrowRight");
    await expect(radios.nth(2)).toBeChecked();
    await expect(radios.nth(2)).toBeFocused();
    // 라벨 전체가 누르는 영역(44px)
    expect(Math.round((await box(group.locator("label.choice").first())).height)).toBeGreaterThanOrEqual(44);
    await noHorizontalOverflow(page);
  });
}

test("공개 프로필: 아바타·닉네임이 위에, 소개·태그는 따로 구역이고 비어 있으면 안내한다", async ({ page }) => {
  const bob = await ApiActor.login("bob");
  const bobId = (await (await bob.get("/api/auth/me")).json()).id as number;
  const before = await (await bob.get("/api/me/profile")).json();
  await bob.patch("/api/me/profile", { nickname: before.nickname, bio: "", tags: [] });
  try {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginInBrowser(page, "alice");
    await page.goto(`/profiles/${bobId}`);
    const heading = page.getByRole("heading", { level: 1, name: before.nickname });
    await expect(heading).toBeVisible();
    const avatar = page.locator(".profile-head .avatar");
    await expect(avatar).toHaveText(Array.from(before.nickname as string)[0]);
    expect((await box(avatar)).y).toBeLessThan((await box(heading)).y + 1);
    await expect(page.getByRole("region", { name: "자기소개" })).toContainText("아직 소개가 없어요.");
    await expect(page.getByRole("region", { name: "관심 태그" })).toContainText("아직 등록한 관심 태그가 없어요.");
    await expect(page.getByText("bob", { exact: true })).toHaveCount(0);
    await noHorizontalOverflow(page);
  } finally {
    await bob.patch("/api/me/profile", { nickname: before.nickname, bio: before.bio ?? "", tags: before.tags });
    await bob.dispose();
  }
});

test("375px 모임 상세: 긴 이름·소개도 넘치지 않고, 머리 상자 → 모임 안 메뉴 → 소개 순으로 구분된다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const name = uniqueTitle("아주긴모임이름띄어쓰기없이길게이어지는경우").slice(0, 60);
  const created = await (await operator.post("/api/clubs", { name, intro: "긴소개".repeat(300), place: "아주긴장소".repeat(30), mode: "MIXED" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  try {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginInBrowser(page, "operator");
    await page.goto(`/clubs/${created.id}`);
    const head = page.locator(".club-head");
    await expect(head.getByRole("heading", { level: 1 })).toBeVisible();
    const tabs = page.getByRole("navigation", { name: "모임 안 메뉴" });
    const intro = page.getByRole("region", { name: "소개" });
    const [h, t, i] = [await box(head), await box(tabs), await box(intro)];
    expect(t.y).toBeGreaterThanOrEqual(h.y + h.height);
    expect(i.y).toBeGreaterThanOrEqual(t.y + t.height);
    // 메뉴가 많아도 메뉴 띠 안에서만 가로로 민다
    expect(t.x + t.width).toBeLessThanOrEqual(375);
    await noHorizontalOverflow(page);
    // 역할별로 버튼 수가 달라도 같은 높이
    const heights = await head.locator(".club-actions").locator("button, a").evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().height)));
    expect(heights.length).toBeGreaterThan(0);
    expect(new Set(heights).size).toBe(1);
  } finally {
    await operator.delete(`/api/clubs/${created.id}`);
    await operator.dispose();
  }
});

for (const width of [1280, 375]) {
  test(`${width}px 회차 관리: 정보·정원 변경·명단·관리 작업 구역, 정원 입력과 버튼 아래선이 맞고 삭제는 따로`, async ({ page }) => {
    const bob = await ApiActor.login("bob");
    const title = uniqueTitle("관리 레이아웃");
    const res = await bob.post("/api/sessions", { title, startsAt: new Date(Date.now() + 72 * 3600e3).toISOString(), location: "레이아웃 장소", capacity: 2 });
    const id = (await res.json()).id as number;
    try {
      await page.setViewportSize({ width, height: 900 });
      await loginInBrowser(page, "bob");
      await page.goto(`/sessions/${id}/manage`);
      for (const name of ["회차 정보", "정원 변경", "확정 명단", "관리 작업"]) await expect(page.getByRole("region", { name })).toBeVisible();
      await sameRow([page.getByLabel("새 정원"), page.getByRole("button", { name: "정원 변경" })]);
      const danger = page.getByRole("group", { name: "회차 삭제" });
      const edit = await box(page.getByRole("link", { name: "제목·시작·장소 수정" }));
      expect((await box(danger)).y).toBeGreaterThan(edit.y + edit.height);
      // 삭제는 기존처럼 확인을 거친다: 취소하면 그대로
      page.once("dialog", (d) => d.dismiss());
      await danger.getByRole("button", { name: "회차 삭제" }).click();
      await expect(page).toHaveURL(new RegExp(`/sessions/${id}/manage$`));
      await noHorizontalOverflow(page);
    } finally {
      await bob.delete(`/api/sessions/${id}`);
      await bob.dispose();
    }
  });
}

test("375px 권한 관리: 역할표 네 칸과 설명이 화면 안에 들어온다 (가로로 밀지 않아도 '만든 사람' 칸이 보임)", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  const created = await (await operator.post("/api/clubs", { name: uniqueTitle("역할표"), intro: "소개", place: "온라인", mode: "ONLINE" })).json();
  expect((await operator.post(`/api/operator/clubs/${created.id}/approve`, { version: created.version })).status()).toBe(200);
  try {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginInBrowser(page, "operator");
    await page.goto(`/clubs/${created.id}/roles`);
    const table = page.locator(".role-table table");
    await expect(table.getByRole("columnheader", { name: "만든 사람" })).toBeVisible();
    const scroll = await page.locator(".role-table").evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(scroll, "역할표가 상자보다 넓다").toBeLessThanOrEqual(0);
    const header = await box(table.getByRole("columnheader", { name: "만든 사람" }));
    expect(header.x + header.width).toBeLessThanOrEqual(375);
    await noHorizontalOverflow(page);
  } finally {
    await operator.delete(`/api/clubs/${created.id}`);
    await operator.dispose();
  }
});
