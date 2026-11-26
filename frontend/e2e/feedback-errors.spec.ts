import { expect, test, type Page } from "@playwright/test";

import { ApiActor, loginInBrowser, menuLink, uniqueTitle } from "./support";

// 확장 3단계 화면: 회원의 개선 요청 접수·확인, 개발자의 개선사항 관리(보류 사유 필수)와 에러 로그, 권한 없는 직접 접근.
const visible = (page: Page, name: string) => page.getByLabel(name, { exact: true }).filter({ visible: true });
const PASSWORD = "e2e-dev-pass-1!";

// 새 계정 가입(가입 제한 창에 걸리면 한 번 기다림)·역할 부여·브라우저 두 개라 기본 30초보다 길다
test.describe.configure({ timeout: 90_000 });

/** 새 회원을 만들고 운영자가 개발자 역할을 준다(시드 계정 역할은 건드리지 않는다). */
async function newDeveloper() {
  const loginId = `dev${Date.now().toString(36).slice(-6)}`;
  const dev = await ApiActor.signup(loginId, PASSWORD);
  const id = (await (await dev.get("/api/auth/me")).json()).id as number;
  await dev.dispose();
  const operator = await ApiActor.login("operator");
  expect((await operator.put(`/api/operator/roles/${id}/DEVELOPER`, { reason: "e2e 개발자" })).status()).toBe(200);
  await operator.dispose();
  return loginId;
}

/** 가림 규칙(긴 영숫자)에 걸리지 않는 고유 한글 표지 */
const hangulMarker = () => [...Date.now().toString(16).slice(-8)].map((c) => String.fromCharCode(0xac00 + parseInt(c, 16))).join("");

test("회원이 개선 요청을 보내고, 개발자가 사유와 함께 보류하면 회원 화면에 상태·사유가 보인다", async ({ page, browser }) => {
  const title = uniqueTitle("검색 개선");
  await loginInBrowser(page, "bob");
  await menuLink(page, "내 활동", "개선 요청");
  await expect(page.getByRole("heading", { name: "개선 요청" })).toBeVisible();
  await visible(page, "종류").selectOption("IDEA");
  await visible(page, "제목").fill(title);
  await visible(page, "내용").fill("검색 결과에 장소도 보여 주세요");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByRole("status").filter({ hasText: "개선 요청을 보냈어요" })).toBeVisible();
  const row = page.getByRole("main").getByRole("listitem").filter({ hasText: title });
  await expect(row).toContainText("접수");

  const devContext = await browser.newContext();
  const dev = await devContext.newPage();
  await loginInBrowser(dev, await newDeveloper(), PASSWORD);
  await menuLink(dev, "개발", "개선사항 관리");
  await expect(dev.getByRole("heading", { name: "개선사항 관리" })).toBeVisible();
  await visible(dev, "검색어").fill(title);
  await dev.getByRole("main").getByRole("button", { name: "검색", exact: true }).click();
  await dev.getByRole("main").getByRole("button", { name: title }).click();
  await visible(dev, "새 상태").selectOption("ON_HOLD");
  await dev.getByRole("button", { name: "상태 저장" }).click();
  await expect(dev.getByRole("alert").filter({ hasText: "사유" })).toBeVisible();
  await visible(dev, "사유").fill("다음 분기에 검토");
  await dev.getByRole("button", { name: "상태 저장" }).click();
  await expect(dev.getByRole("status").filter({ hasText: "보류" })).toBeVisible();
  await devContext.close();

  await page.reload();
  await row.getByRole("link", { name: title }).click();
  await expect(page.getByTestId("feedback-status")).toHaveText("보류");
  await expect(page.getByText("다음 분기에 검토")).toBeVisible();
});

test("화면 오류는 가려진 채 에러 로그에 모이고, 개발자만 본다", async ({ page, browser }) => {
  const marker = hangulMarker();
  const devContext = await browser.newContext();
  const dev = await devContext.newPage();
  await loginInBrowser(dev, await newDeveloper(), PASSWORD);
  await dev.goto("/board");
  const reported = dev.waitForResponse((r) => r.url().endsWith("/api/errors") && r.request().method() === "POST");
  await dev.evaluate((m) => setTimeout(() => {
    throw new Error(`화면 오류 ${m} password=hunter2`);
  }), marker);
  expect((await reported).status()).toBe(202);

  await menuLink(dev, "개발", "에러 로그");
  await expect(dev.getByRole("heading", { name: "에러 로그" })).toBeVisible();
  await visible(dev, "검색어").fill(marker);
  await dev.getByRole("main").getByRole("button", { name: "검색", exact: true }).click();
  const item = dev.getByTestId("error-row").filter({ hasText: marker });
  await expect(item).toContainText("화면");
  await expect(item).toContainText("/board");
  await expect(item).not.toContainText("hunter2");
  await devContext.close();

  await loginInBrowser(page, "bob");
  await page.goto("/dev/errors");
  await expect(page.getByRole("alert").filter({ hasText: "개발자만" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "사이드 메뉴" }).getByRole("button", { name: "개발" })).toHaveCount(0);
});
