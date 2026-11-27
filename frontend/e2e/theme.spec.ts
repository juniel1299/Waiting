import { expect, test, type Page } from "@playwright/test";

import { loginInBrowser } from "./support";

// 라이트·다크 모드 전환: 처음에는 기기 설정을 따르고, 버튼으로 바꾸면 이 브라우저에 기억해 다음 방문에도 깜빡임 없이 적용한다.
const LIGHT_BG = "rgb(255, 255, 255)";
const DARK_BG = "rgb(10, 10, 10)";

const bodyBg = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
const theme = (page: Page) => page.evaluate(() => document.documentElement.dataset.theme ?? null);
const toggle = (page: Page) => page.getByRole("button", { name: "다크 모드" }).filter({ visible: true });

test("처음에는 기기 설정(라이트)을 따르고, 버튼으로 다크로 바꾸면 새로 고쳐도 유지된다", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await loginInBrowser(page, "alice");
  expect(await theme(page)).toBeNull();
  expect(await bodyBg(page)).toBe(LIGHT_BG);
  await expect(toggle(page)).toHaveAttribute("aria-pressed", "false");

  await toggle(page).click();
  await expect(toggle(page)).toHaveAttribute("aria-pressed", "true");
  expect(await theme(page)).toBe("dark");
  await expect.poll(() => bodyBg(page)).toBe(DARK_BG);

  await page.reload();
  await expect(toggle(page)).toHaveAttribute("aria-pressed", "true");
  expect(await bodyBg(page)).toBe(DARK_BG);

  // 다시 누르면 라이트(기기 설정과 관계없이 고른 값)
  await toggle(page).click();
  expect(await theme(page)).toBe("light");
  await expect.poll(() => bodyBg(page)).toBe(LIGHT_BG);
});

test("기기 설정이 다크면 처음부터 다크이고, 라이트로 고르면 기기 설정보다 앞선다", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/login");
  expect(await bodyBg(page)).toBe(DARK_BG);
  await expect(toggle(page)).toHaveAttribute("aria-pressed", "true");
  await toggle(page).click();
  await expect.poll(() => bodyBg(page)).toBe(LIGHT_BG);
  await page.reload();
  expect(await bodyBg(page)).toBe(LIGHT_BG);
});

test("고른 모드는 화면 스크립트가 뜨기 전에 적용된다 (새로 고칠 때 반대 색으로 깜빡이지 않음)", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/login");
  await toggle(page).click();
  await expect.poll(() => theme(page)).toBe("dark");
  // 앱 스크립트를 막아도(하이드레이션 전 상태) 첫 그림이 다크
  await page.route(/\/_next\/static\/.*\.js/, (route) => route.abort());
  await page.goto("/login");
  expect(await theme(page)).toBe("dark");
  expect(await bodyBg(page)).toBe(DARK_BG);
});

test("키보드로 전환 버튼에 가서 Space 로 바꾼다, 로그인 전 화면에도 있다", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  for (const path of ["/", "/login", "/signup", "/notices"]) {
    await page.goto(path);
    await expect(toggle(page), path).toBeVisible();
  }
  await page.goto("/login");
  await toggle(page).focus();
  await page.keyboard.press("Space");
  await expect(toggle(page)).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => bodyBg(page)).toBe(DARK_BG);
});

test("375px 상단 막대에 전환 버튼이 들어가고 가로로 넘치지 않는다", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.emulateMedia({ colorScheme: "light" });
  await loginInBrowser(page, "alice");
  const button = toggle(page);
  await expect(button).toBeVisible();
  const box = await button.boundingBox();
  expect(Math.round(box!.height)).toBe(44);
  expect(box!.x + box!.width).toBeLessThanOrEqual(375);
  // 옆 버튼들도 글자가 두 줄로 꺾이지 않는다(꺾이면 44px 보다 커진다)
  for (const name of ["메뉴", "로그아웃"]) {
    const b = await page.getByRole("button", { name, exact: true }).boundingBox();
    expect(Math.round(b!.height), name).toBe(44);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});

/** WCAG 대비율 (rgb() 문자열 두 개). */
function contrast(a: string, b: string) {
  const lum = (c: string) => {
    const [r, g, bl] = c.match(/\d+(\.\d+)?/g)!.slice(0, 3).map(Number).map((v) => {
      const x = v / 255;
      return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

for (const mode of ["light", "dark"] as const) {
  test(`${mode}: 채운 버튼(기본·위험) 글자와 배경 대비가 4.5:1 이상`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: mode });
    await page.goto("/login");
    const button = page.getByRole("button", { name: "로그인", exact: true });
    const [fg, bg] = await button.evaluate((el) => [getComputedStyle(el).color, getComputedStyle(el).backgroundColor]);
    expect(contrast(fg, bg), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
    const danger = await page.evaluate(() => {
      const b = document.createElement("button");
      b.className = "danger";
      b.textContent = "삭제";
      document.querySelector("main")!.append(b);
      const s = getComputedStyle(b);
      return [s.color, s.backgroundColor];
    });
    expect(contrast(danger[0], danger[1]), `위험 ${danger}`).toBeGreaterThanOrEqual(4.5);
  });
}
