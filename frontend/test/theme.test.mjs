// 라이트·다크 모드 결정 단위 검증. 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";

import { effectiveTheme, nextTheme, parseTheme, THEME_INIT_SCRIPT, THEME_KEY } from "../src/lib/theme.ts";

test("저장값은 light·dark 만 인정하고 나머지는 '고르지 않음'", () => {
  assert.equal(parseTheme("light"), "light");
  assert.equal(parseTheme("dark"), "dark");
  for (const v of [null, undefined, "", "Dark", "system", "<script>"]) assert.equal(parseTheme(v), null);
});

test("고른 값이 기기 설정보다 앞서고, 없으면 기기 설정을 따른다", () => {
  assert.equal(effectiveTheme(null, true), "dark");
  assert.equal(effectiveTheme(null, false), "light");
  assert.equal(effectiveTheme("light", true), "light");
  assert.equal(effectiveTheme("dark", false), "dark");
});

test("전환은 반대 모드로", () => {
  assert.equal(nextTheme("light"), "dark");
  assert.equal(nextTheme("dark"), "light");
});

test("첫 그림 스크립트는 같은 저장 키를 읽고, 허용한 값만 넣으며 저장소가 막혀도 오류를 내지 않는다", () => {
  assert.ok(THEME_INIT_SCRIPT.includes(THEME_KEY));
  const run = (stored, throws = false) => {
    const html = { dataset: {} };
    const storage = { getItem: () => { if (throws) throw new Error("blocked"); return stored; } };
    new Function("localStorage", "document", THEME_INIT_SCRIPT)(storage, { documentElement: html });
    return html.dataset.theme;
  };
  assert.equal(run("dark"), "dark");
  assert.equal(run("light"), "light");
  assert.equal(run("evil"), undefined);
  assert.equal(run(null), undefined);
  assert.equal(run("dark", true), undefined);
});
