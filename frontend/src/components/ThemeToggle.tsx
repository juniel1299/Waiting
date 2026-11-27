"use client";

import { useSyncExternalStore } from "react";

import { applyTheme, currentTheme, nextTheme, subscribeTheme } from "@/lib/theme";

/** 라이트·다크 전환 버튼. 이름은 "다크 모드"로 고정하고 켜짐은 aria-pressed 로 알린다. */
export function ThemeToggle() {
  // 서버 그림에는 모드를 모르므로 null → 하이드레이션 뒤 실제 값으로
  const theme = useSyncExternalStore(subscribeTheme, currentTheme, () => null);
  const dark = theme === "dark";
  return (
    <button
      type="button"
      className="secondary theme-toggle"
      aria-pressed={theme === null ? undefined : dark}
      onClick={() => applyTheme(nextTheme(currentTheme()))}
      title={dark ? "라이트 모드로 바꾸기" : "다크 모드로 바꾸기"}
    >
      <span aria-hidden="true">{dark ? "☾" : "☀"}</span>
      <span className="theme-label">다크 모드</span>
    </button>
  );
}
