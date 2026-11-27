// 공개 배포의 화면 정리(PR #64)·라이트/다크 전환(PR #65)을 비밀번호 없이 확인한다: 비회원 화면과 데모 체험만.
// 바꾸는 데이터: 데모 시작 브라우저마다 1회(30분 뒤 서버가 지움). 이 브라우저(임시 프로필)의 모드 기억만 바뀐다.
// 실행: node loadtest/public-theme-layout-open-check.mjs   (frontend 의 Playwright 사용, WebKit 은 설치돼 있으면 함께)
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

import { BASE } from "./lib/public-client.mjs";

const require = createRequire(fileURLToPath(new URL("../frontend/package.json", import.meta.url)));
const { chromium, webkit, devices } = require("@playwright/test");

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

const DARK_BG = "rgb(10, 10, 10)";
const LIGHT_BG = "rgb(255, 255, 255)";

function contrast(a, b) {
  const lum = (c) => {
    const [r, g, bl] = c.match(/\d+(\.\d+)?/g).slice(0, 3).map(Number).map((v) => {
      const x = v / 255;
      return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

async function themeChecks(browser, label, contextOptions) {
  const ctx = await browser.newContext({ ...contextOptions, colorScheme: "light", locale: "ko-KR" });
  const page = await ctx.newPage();
  const bg = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  const toggle = () => page.getByRole("button", { name: "다크 모드" }).filter({ visible: true });

  const missing = [];
  for (const path of ["/", "/login", "/signup", "/notices"]) {
    await page.goto(BASE + path);
    // 비회원 화면은 로그인 확인 뒤에 머리 막대를 그리므로 나타날 때까지 기다린다
    if (!(await toggle().waitFor({ timeout: 60_000 }).then(() => true, () => false))) missing.push(path);
  }
  record(`${label}: 로그인 전 화면에 '다크 모드' 버튼`, missing.length === 0, missing.length ? `없음 ${missing}` : "/, /login, /signup, /notices");

  await page.goto(BASE + "/login");
  const startBg = await bg();
  await toggle().click();
  await page.waitForFunction(() => document.documentElement.dataset.theme === "dark");
  await page.waitForFunction((d) => getComputedStyle(document.body).backgroundColor === d, DARK_BG);
  record(`${label}: 기기 설정 라이트 → 버튼으로 다크`, startBg === LIGHT_BG, `${startBg} → ${await bg()}, aria-pressed=${await toggle().getAttribute("aria-pressed")}`);

  // 버튼 색 전환(180ms)이 끝난 뒤 잰다
  await page.waitForTimeout(500);
  const login = page.getByRole("button", { name: "로그인", exact: true });
  const [fg, bgc] = await login.evaluate((el) => [getComputedStyle(el).color, getComputedStyle(el).backgroundColor]);
  record(`${label}: 다크에서 로그인 버튼 글자 대비 4.5:1 이상`, contrast(fg, bgc) >= 4.5, `${fg} on ${bgc} = ${contrast(fg, bgc).toFixed(2)}`);

  await page.reload();
  record(`${label}: 새로 고쳐도 다크 유지`, (await bg()) === DARK_BG, await bg());

  await page.route(/\/_next\/static\/.*\.js/, (route) => route.abort());
  await page.goto(BASE + "/login");
  const before = await page.evaluate(() => [document.documentElement.dataset.theme, getComputedStyle(document.body).backgroundColor]);
  record(`${label}: 화면 스크립트 전에 적용(깜빡임 없음)`, before[0] === "dark" && before[1] === DARK_BG, before.join(" "));
  await ctx.close();
}

async function layoutChecks(browser, label, contextOptions) {
  const ctx = await browser.newContext({ ...contextOptions, locale: "ko-KR", timezoneId: "Asia/Seoul" });
  const page = await ctx.newPage();
  const csrf = await (await page.request.get(BASE + "/api/auth/csrf")).json();
  const start = await page.request.post(BASE + "/api/demo/start", { headers: { [csrf.headerName]: csrf.token } });
  record(`${label}: 데모 시작`, start.status() === 201, `${start.status()}`);
  await page.goto(BASE + "/sessions?from=2026-11-01&to=2026-12-31");
  await page.getByLabel("시작일부터", { exact: true }).filter({ visible: true }).waitFor({ timeout: 60_000 });
  const m = await page.evaluate(() => {
    const form = document.querySelector("main form.search-form");
    const fb = form.getBoundingClientRect();
    const dates = [...form.querySelectorAll("input[type=date]")].map((el) => el.getBoundingClientRect());
    const selects = [...form.querySelectorAll("select")].map((el) => Math.round(el.getBoundingClientRect().height));
    const top = [...document.querySelectorAll(".topbar button")].filter((b) => b.offsetParent).map((b) => Math.round(b.getBoundingClientRect().height));
    return {
      inside: dates.every((d) => d.left >= fb.left && d.right <= fb.right),
      stacked: dates.length === 2 && dates[1].top >= dates[0].bottom - 1,
      selects,
      top,
      overflow: document.documentElement.scrollWidth - innerWidth,
      vw: innerWidth,
    };
  });
  record(`${label}: 회차 목록 날짜 입력이 검색 상자 안·한 줄에 하나`, m.inside && m.stacked, `폭 ${m.vw}px inside=${m.inside} stacked=${m.stacked}`);
  record(`${label}: 선택창·상단 막대 버튼 44px`, [...m.selects, ...m.top].every((h) => h === 44), `선택창 ${m.selects} / 상단 ${m.top}`);
  record(`${label}: 가로 넘침 없음`, m.overflow <= 0, `${m.overflow}`);
  await ctx.close();
}

console.log(`시작 ${new Date().toISOString()} ${BASE}`);
const engines = [["Chromium 375px", chromium, { viewport: { width: 375, height: 812 } }]];
try {
  const wk = await webkit.launch();
  await wk.close();
  engines.push(["WebKit iPhone 17 Pro", webkit, devices["iPhone 17 Pro"]]);
} catch {
  console.log("WebKit 없음: npx playwright install webkit 뒤 다시 실행하면 함께 확인한다");
}
for (const [label, type, options] of engines) {
  const browser = await type.launch();
  await themeChecks(browser, label, options);
  await layoutChecks(browser, label, options);
  await browser.close();
}
const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
