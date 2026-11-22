// 공개 배포의 개편 4단계(공통 상호작용·모션·순번 변경 강조)와 비밀번호 없는 API 를 데모 체험 계정으로만 확인한다.
// 비밀번호 로그인을 하지 않는다. 데모 시작은 4회 (IP 당 15분 10회 한도 안). 데모 체험 데이터는 30분 뒤 서버가 통째로 지운다.
// 실행: node loadtest/public-stage4-check.mjs [스크린샷 저장 폴더]  (Playwright 는 frontend 의 설치본을 쓴다)
import { createRequire } from "node:module";

const require = createRequire(new URL("../frontend/package.json", import.meta.url));
const { chromium, devices } = require("@playwright/test");

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const SHOT_DIR = process.argv[2];
const SORTS = ["SOON", "LATEST_START", "EARLIEST_START", "NEWEST", "MOST_CONFIRMED", "FEWEST_CONFIRMED", "MOST_SEATS"];
const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

/** 쿠키를 들고 다니는 최소 HTTP 클라이언트 (데모 방문자). */
async function apiChecks() {
  const jar = new Map();
  const req = async (method, path) => {
    const headers = { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = jar.get("XSRF-TOKEN") ?? "";
    const started = Date.now();
    const res = await fetch(BASE + path, { method, headers, signal: AbortSignal.timeout(330_000) });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
    const body = await res.json().catch(() => null);
    return { status: res.status, body, ms: Date.now() - started };
  };
  const csrf = await req("GET", "/api/auth/csrf");
  record("CSRF 토큰", csrf.status === 200, `${csrf.status} ${csrf.ms}ms`);
  const start = await req("POST", "/api/demo/start");
  record("데모 시작 API", start.status === 201, `${start.status} ${start.ms}ms 체험 회차 ${start.body?.sessionId}`);
  if (!jar.get("XSRF-TOKEN")) await req("GET", "/api/auth/csrf");
  const sorts = [];
  for (const sort of SORTS) sorts.push([sort, (await req("GET", `/api/sessions/search?scope=all&status=ALL&sort=${sort}`)).status]);
  record("검색 API 정렬 7종 (scope=all)", sorts.every(([, s]) => s === 200), sorts.map(([n, s]) => `${n} ${s}`).join(", "));
  const removed = await req("GET", "/api/sessions/search?sort=TITLE");
  record("검색 API 없앤 정렬 거절", removed.status === 400, `sort=TITLE ${removed.status} ${removed.body?.code ?? ""}`);
  jar.clear();
  const anon = [];
  for (const path of ["/api/operator/usage", "/api/operator/accounts", "/api/operator/actions", "/api/sessions/search"]) {
    anon.push(`${path} ${(await req("GET", path)).status}`);
  }
  record("비로그인 보호 API 401", anon.every((s) => s.endsWith("401")), anon.join(", "));
}

const style = (locator, prop) => locator.evaluate((el, p) => getComputedStyle(el).getPropertyValue(p), prop);

/** 데모 체험으로 들어가 4단계 상호작용을 확인한다. kind: desktop | mobile | reduced. */
async function browserChecks(browser, kind) {
  const options = {
    desktop: { viewport: { width: 1280, height: 900 } },
    mobile: { ...devices["iPhone 13"], viewport: { width: 375, height: 812 } },
    reduced: { viewport: { width: 1280, height: 900 }, reducedMotion: "reduce" },
  }[kind];
  const context = await browser.newContext({ ...options, locale: "ko-KR", timezoneId: "Asia/Seoul" });
  const page = await context.newPage();
  try {
    await page.goto(BASE + "/");
    await page.getByRole("button", { name: "데모 체험하기" }).click();
    await page.waitForURL(/\/sessions\/\d+$/, { timeout: 330_000 });
    const detailUrl = page.url();

    // 목록: 메뉴 현재 위치, 카드 클릭 영역, 모션
    await page.goto(BASE + "/sessions");
    const nav = page.getByRole("navigation", { name: "주 메뉴" });
    const current = await nav.getByRole("link", { name: "회차 목록" }).getAttribute("aria-current");
    record(`[${kind}] 메뉴 현재 위치`, current === "page", `회차 목록 aria-current=${current}`);
    const card = page.locator(".cards .card:visible").first();
    await card.waitFor();
    if (kind !== "mobile") {
      await card.hover();
      await page.waitForTimeout(400);
      const transform = await style(card, "transform");
      const duration = parseFloat(await style(card, "transition-duration"));
      const ok = kind === "reduced" ? transform === "none" && duration < 0.02 : transform !== "none" && duration >= 0.15 && duration <= 0.2;
      record(`[${kind}] 카드 hover 모션`, ok, `transform=${transform}, transition=${duration}s`);
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    record(`[${kind}] 가로 스크롤 없음`, overflow <= 0, `scrollWidth - clientWidth = ${overflow}`);

    // 버튼 키보드 포커스 링: 정렬 칸에서 Tab → 검색 버튼
    if (kind === "desktop") {
      await page.getByLabel("정렬", { exact: true }).filter({ visible: true }).focus();
      await page.keyboard.press("Tab");
      const focused = page.locator(":focus");
      const name = await focused.textContent();
      const outline = await style(focused, "outline-style");
      record("[desktop] 버튼 키보드 포커스 링", name === "검색" && outline !== "none", `포커스 '${name}', outline-style=${outline}`);
    }

    // 카드의 제목이 아닌 곳(장소)을 눌러도 상세로
    await card.locator("dd").nth(1).click({ force: true });
    await page.waitForURL(/\/sessions\/\d+$/);
    record(`[${kind}] 카드 클릭 영역`, page.url() === detailUrl, `장소 글자 클릭 → ${new URL(page.url()).pathname}`);

    // 대기 신청 → 샘플 참가자 취소 → 확정 강조와 안내
    const mine = page.getByTestId("my-reservation");
    await page.getByRole("button", { name: "대기 신청" }).click();
    await mine.filter({ hasText: "대기 1번" }).waitFor();
    await page.getByRole("button", { name: "샘플 참가자 취소" }).click();
    await mine.filter({ hasText: "예약 확정" }).waitFor({ timeout: 15_000 });
    const changed = await mine.getAttribute("data-changed");
    const announced = await page.getByRole("status").filter({ hasText: "대기 순번이 되어 예약이 확정됐어요." }).count();
    if (SHOT_DIR && kind === "mobile") await page.screenshot({ path: `${SHOT_DIR}/stage4-public-demo-confirm-mobile.png` });
    record(`[${kind}] 확정 강조·안내`, changed === "true" && announced > 0, `data-changed=${changed}, role=status 안내 ${announced}건`);
    await page.waitForTimeout(2_000);
    const after = await mine.getAttribute("data-changed");
    record(`[${kind}] 강조 해제`, after === null, `2초 뒤 data-changed=${after}`);
  } catch (e) {
    record(`[${kind}] 브라우저 점검`, false, e.message.split("\n")[0]);
  } finally {
    await context.close();
  }
}

console.log(`시작 ${new Date().toISOString()} ${BASE}`);
await apiChecks();
const browser = await chromium.launch();
for (const kind of ["desktop", "mobile", "reduced"]) await browserChecks(browser, kind);
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`결과: 성공 ${results.length - failed.length} / 실패 ${failed.length}`);
process.exitCode = failed.length ? 1 : 0;
