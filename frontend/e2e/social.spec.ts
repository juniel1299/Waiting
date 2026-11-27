import { expect, test } from "@playwright/test";

import { loginInBrowser, menuLink } from "./support";

// 확장 5단계 화면: 공급자 설정(환경 변수)이 없으면 '준비 중'으로 비활성, 로그인 화면에는 소셜 버튼이 없다.
// 연결·로그인 흐름 자체는 백엔드 테스트가 가짜 공급자 서버로 확인한다(SocialAccountApiTests).

test("내 정보 > 로그인 계정 연결: 공급자마다 상태를 보이고, 설정이 없으면 준비 중으로 연결할 수 없다", async ({ page }) => {
  await loginInBrowser(page, "bob");
  await menuLink(page, "내 정보", "로그인 계정 연결");
  await expect(page.getByRole("heading", { name: "로그인 계정 연결" })).toBeVisible();
  for (const name of ["카카오", "네이버"]) {
    const row = page.getByTestId(`social-${name}`);
    await expect(row).toContainText("준비 중");
    await expect(row.getByRole("link", { name: `${name} 연결하기` })).toHaveCount(0);
  }
  await expect(page.getByText("아이디·비밀번호 로그인은 그대로 남아요")).toBeVisible();
});

test("콜백 결과 안내: 연결 화면과 로그인 화면이 ?error 를 한국어로 알린다", async ({ page }) => {
  await page.goto("/login?error=SOCIAL_NOT_LINKED");
  await expect(page.getByRole("alert").filter({ hasText: "연결된 계정이 없어요" })).toBeVisible();
  await expect(page.getByRole("link", { name: /카카오로 로그인/ })).toHaveCount(0);

  await loginInBrowser(page, "bob");
  await page.goto("/me/accounts?error=SOCIAL_ALREADY_LINKED");
  await expect(page.getByRole("alert").filter({ hasText: "이미 다른 회원에게 연결" })).toBeVisible();
});
