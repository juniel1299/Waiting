import { expect, test } from "@playwright/test";

import { ApiActor, loginInBrowser, uniqueTitle } from "./support";

// 확장 4단계 대시보드: 현재 수치는 카드+표(일반·데모 나눔), 누적은 표 ↔ 차트 전환(선택 기억), 차트 수치는 아래 표에도, 빈 기간·오류 상태.

test("현재 수치 카드는 표와 같은 값이고, 차트로 바꾸면 선·막대 차트와 같은 수치의 표가 보이며 새로 고쳐도 차트로 남는다", async ({ page }) => {
  const operator = await ApiActor.login("operator");
  await operator.createSession(uniqueTitle("차트 회차"), 2);
  await operator.dispose();

  await loginInBrowser(page, "operator");
  await expect(page.getByRole("heading", { name: "운영자 · 사용량" })).toBeVisible();
  const tableValue = await page.getByTestId("current-regular-sessions").textContent();
  await expect(page.getByTestId("card-sessions").getByTestId("card-regular")).toHaveText(tableValue ?? "");
  await expect(page.getByTestId("card-sessions")).toContainText("데모 체험");

  const totalCreated = await page.getByTestId("total-regular-SESSION_CREATED").textContent();
  await page.getByRole("button", { name: "차트", exact: true }).click();
  await expect(page.getByRole("button", { name: "차트", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByLabel("지표", { exact: true }).selectOption("SESSION_CREATED");
  const line = page.getByRole("img", { name: /회차 생성 일별 추이/ });
  await expect(line).toBeVisible();
  await expect(line).toHaveAccessibleName(new RegExp(`일반 합계 ${totalCreated}`));
  await expect(page.getByRole("img", { name: /지표별 합계/ })).toBeVisible();
  await expect(page.getByTestId("chart-sum-regular")).toHaveText(totalCreated ?? "");

  await page.reload();
  await expect(page.getByRole("button", { name: "차트", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "표", exact: true }).click();
  await expect(page.getByTestId("total-regular-SESSION_CREATED")).toBeVisible();
});

test("기록이 없는 기간은 빈 상태를, 불러오기 오류는 다시 시도 버튼을 보인다", async ({ page }) => {
  await loginInBrowser(page, "operator");
  await expect(page.getByRole("heading", { name: "운영자 · 사용량" })).toBeVisible();
  await page.getByRole("button", { name: "차트", exact: true }).click();
  await page.getByLabel("집계 시작일").fill("2020-01-01");
  await page.getByLabel("집계 종료일").fill("2020-01-02");
  await page.getByRole("button", { name: "기간 적용" }).click();
  await expect(page.getByText("이 기간에는 기록이 없어요.")).toBeVisible();
  await page.getByRole("button", { name: "표", exact: true }).click();

  let failures = 0;
  await page.route("**/api/operator/usage**", (route) => {
    if (failures > 0) return route.continue();
    failures += 1;
    return route.fulfill({ status: 500, contentType: "application/json", body: '{"code":"INTERNAL_ERROR","message":"잠시 문제가 생겼어요."}' });
  });
  await page.reload();
  await expect(page.getByRole("alert").filter({ hasText: "사용량을 불러오지 못했어요" })).toBeVisible();
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByTestId("current-regular-sessions")).toBeVisible();
});
