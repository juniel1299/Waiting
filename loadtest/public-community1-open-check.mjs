// 공개 배포의 커뮤니티 1단계를 비밀번호 없이 확인한다: 비회원(공지 열람·게시판 401·화면 로그인 이동)과 데모 체험 계정(게시판 조회만).
// 비밀번호 로그인을 하지 않고 새 글을 만들지 않는다(데모·비회원은 쓸 수 없음). 데모 시작 2회, 데모 데이터는 30분 뒤 서버가 지운다.
// 실행: node loadtest/public-community1-open-check.mjs [스크린샷 폴더]
import { createRequire } from "node:module";

const require = createRequire(new URL("../frontend/package.json", import.meta.url));
const { chromium } = require("@playwright/test");

const BASE = process.env.BASE_URL ?? "https://waiting-mauve.vercel.app";
const SHOT_DIR = process.argv[2];
const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok });
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

class Client {
  jar = new Map();
  async req(method, path, body) {
    if (method !== "GET" && !this.jar.get("XSRF-TOKEN")) await this.req("GET", "/api/auth/csrf");
    const headers = { cookie: [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ") };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.jar.get("XSRF-TOKEN") ?? "";
    if (body) headers["content-type"] = "application/json";
    const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(330_000) });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      const value = pair.slice(i + 1);
      if (value) this.jar.set(pair.slice(0, i), value);
      else this.jar.delete(pair.slice(0, i));
    }
    return { status: res.status, body: await res.json().catch(() => null) };
  }
}

console.log(`시작 ${new Date().toISOString()} ${BASE}`);
const anon = new Client();
const notices = await anon.req("GET", "/api/boards/notice/posts?size=5");
record("비회원 공지 목록", notices.status === 200 && notices.body?.canWrite === false, `${notices.status} ${notices.body?.totalElements}건 canWrite=${notices.body?.canWrite}`);
const firstNotice = notices.body?.items?.[0];
if (firstNotice) {
  const detail = await anon.req("GET", `/api/posts/${firstNotice.id}`);
  record("비회원 공지 상세", detail.status === 200 && typeof detail.body?.body === "string", `${detail.status} canEdit=${detail.body?.canEdit} canDelete=${detail.body?.canDelete}`);
} else {
  record("비회원 공지 상세", true, "공지가 0건이라 건너뜀");
}
const community = await anon.req("GET", "/api/boards/community/posts");
record("비회원 회원 게시판 API 401", community.status === 401, `${community.status} ${community.body?.code}`);
const badPage = await anon.req("GET", "/api/boards/notice/posts?page=0");
const badBoard = await anon.req("GET", "/api/boards/nope/posts");
record("검색·쪽 검증", badPage.status === 400 && badBoard.status !== 200, `page=0 ${badPage.status}, 없는 게시판 ${badBoard.status}`);
const anonWrite = await anon.req("POST", "/api/boards/notice/posts", { title: "x", body: "y" });
record("비회원 공지 작성 거절", anonWrite.status === 401, `${anonWrite.status}`);

const demo = new Client();
const start = await demo.req("POST", "/api/demo/start");
const demoList = await demo.req("GET", "/api/boards/community/posts?q=QA&field=TITLE&page=1&size=5");
const demoWrite = await demo.req("POST", "/api/boards/community/posts", { title: "데모", body: "쓰기" });
const demoNotice = await demo.req("POST", "/api/boards/notice/posts", { title: "데모", body: "공지" });
record("데모 시작", start.status === 201, `${start.status}`);
record("데모 게시판 조회 200·검색 파라미터", demoList.status === 200 && demoList.body?.canWrite === false && demoList.body?.size === 5, `${demoList.status} ${demoList.body?.totalElements}건 canWrite=${demoList.body?.canWrite}`);
record("데모 게시판 작성 403", demoWrite.status === 403 && demoWrite.body?.code === "BOARD_WRITE_FORBIDDEN", `${demoWrite.status} ${demoWrite.body?.code}`);
record("데모 공지 작성 403", demoNotice.status === 403, `${demoNotice.status} ${demoNotice.body?.code}`);

const browser = await chromium.launch();
try {
  const anonPage = await (await browser.newContext({ viewport: { width: 375, height: 812 }, locale: "ko-KR", timezoneId: "Asia/Seoul" })).newPage();
  await anonPage.goto(BASE + "/notices");
  await anonPage.getByRole("heading", { name: "공지사항" }).waitFor({ timeout: 60_000 });
  const login = await anonPage.getByRole("link", { name: "로그인" }).count();
  const write = await anonPage.getByRole("link", { name: "글쓰기" }).count();
  const overflow = await anonPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (SHOT_DIR) await anonPage.screenshot({ path: `${SHOT_DIR}/community1-public-notices-mobile.png` });
  record("비회원 공지 화면 375px", login > 0 && write === 0 && overflow <= 0, `로그인 링크 ${login}, 글쓰기 ${write}, 가로 넘침 ${overflow}`);
  await anonPage.goto(BASE + "/board");
  await anonPage.waitForURL(/\/login$/, { timeout: 60_000 });
  record("비회원 게시판 화면 → 로그인", anonPage.url().endsWith("/login"), new URL(anonPage.url()).pathname);

  const demoPage = await (await browser.newContext({ viewport: { width: 375, height: 812 }, locale: "ko-KR", timezoneId: "Asia/Seoul" })).newPage();
  await demoPage.goto(BASE + "/");
  await demoPage.getByRole("button", { name: "데모 체험하기" }).click();
  await demoPage.waitForURL(/\/sessions\/\d+$/, { timeout: 330_000 });
  await demoPage.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "게시판" }).click();
  await demoPage.getByRole("heading", { name: "회원 게시판" }).waitFor();
  const demoWriteLink = await demoPage.getByRole("link", { name: "글쓰기" }).count();
  const readOnly = await demoPage.getByText("체험 중에는 글을 읽기만 할 수 있어요.").count();
  const demoOverflow = await demoPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  record("데모 게시판 화면 375px", demoWriteLink === 0 && readOnly === 1 && demoOverflow <= 0, `글쓰기 ${demoWriteLink}, 읽기 전용 안내 ${readOnly}, 가로 넘침 ${demoOverflow}`);
} catch (e) {
  record("브라우저 점검", false, e.message.split("\n")[0]);
} finally {
  await browser.close();
}
const failed = results.filter((r) => !r.ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
