import { expect, test, type Page } from "@playwright/test";

import { appAlert } from "./support";

const PASSWORD = "signup-pass-1!";

function newLoginId() {
  return `e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
}

async function fillSignup(page: Page, loginId: string, password: string, confirm: string) {
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByLabel("비밀번호 확인").fill(confirm);
  await page.getByRole("button", { name: "가입하기" }).click();
}

test("첫 화면에서 가입 → 로그인 화면으로 안내 → 가입한 계정으로 로그인하면 참가자 화면", async ({ page }) => {
  const loginId = newLoginId();
  await page.goto("/");
  await page.getByRole("link", { name: "회원가입" }).click();
  await expect(page).toHaveURL(/\/signup$/);

  await fillSignup(page, loginId, PASSWORD, PASSWORD);
  await expect(page).toHaveURL(/\/login/);
  // Next 는 이전 화면을 숨긴 채 남겨 두므로 보이는 칸만 찾는 role 로 고른다
  await expect(page.getByRole("status")).toContainText("가입이 완료됐어요");
  await expect(page.getByRole("textbox", { name: "아이디" })).toHaveValue(loginId);

  await page.getByRole("textbox", { name: "비밀번호" }).fill(PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.getByText(`${loginId} 님`)).toBeVisible();
});

test("이미 있는 아이디면 아이디 칸에 안내한다", async ({ page }) => {
  const loginId = newLoginId();
  await page.goto("/signup");
  await fillSignup(page, loginId, PASSWORD, PASSWORD);
  await expect(page).toHaveURL(/\/login/);

  await page.goto("/signup");
  await fillSignup(page, loginId, PASSWORD, PASSWORD);
  await expect(page.getByLabel("아이디")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByText("이미 사용 중인 아이디예요")).toBeVisible();
  await expect(page).toHaveURL(/\/signup$/);
});

test("형식 오류·비밀번호 불일치는 보내기 전에 칸별로 안내한다", async ({ page }) => {
  let requests = 0;
  page.on("request", (r) => {
    if (r.url().endsWith("/api/auth/signup")) requests += 1;
  });
  await page.goto("/signup");
  await fillSignup(page, "ab", "short", "other");
  await expect(page.getByText("영문 소문자·숫자·밑줄(_)로 4~20자")).toBeVisible();
  await expect(page.getByText("8자 이상")).toBeVisible();
  await expect(page.getByText("비밀번호 확인이 일치하지 않아요")).toBeVisible();
  await expect(page.getByLabel("아이디")).toBeFocused();
  // 오류 문구는 칸 이름이 아니라 설명으로 연결된다
  await expect(page.getByRole("textbox", { name: "아이디", exact: true })).toHaveAccessibleDescription(
    /영문 소문자·숫자·밑줄/,
  );
  expect(requests).toBe(0);
});

test("로그인 화면에서도 회원가입으로 갈 수 있고, 데모 체험 경로는 그대로다", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "/signup");
  await page.getByRole("button", { name: "데모 체험하기" }).click();
  await expect(page).toHaveURL(/\/sessions\/\d+$/);
  await expect(appAlert(page)).toHaveCount(0);
});
