// 라이트·다크 모드: 고르지 않으면 기기 설정(prefers-color-scheme)을 따르고, 고르면 이 브라우저에 기억한다.
// <html data-theme> 가 있으면 CSS 가 기기 설정보다 그 값을 앞세운다(globals.css).

export type Theme = "light" | "dark";

export const THEME_KEY = "daumjari-theme";
const CHANGE_EVENT = "daumjari-theme-change";

/** 첫 그림 전에 <head> 에서 도는 스크립트: 기억한 모드를 바로 적용해 반대 색으로 깜빡이지 않게 한다. */
export const THEME_INIT_SCRIPT = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

export function parseTheme(value: string | null | undefined): Theme | null {
  return value === "light" || value === "dark" ? value : null;
}

/** 지금 보이는 모드: 고른 값이 있으면 그것, 없으면 기기 설정. */
export function effectiveTheme(chosen: Theme | null, systemDark: boolean): Theme {
  return chosen ?? (systemDark ? "dark" : "light");
}

export function nextTheme(current: Theme): Theme {
  return current === "dark" ? "light" : "dark";
}

const systemQuery = () => window.matchMedia("(prefers-color-scheme: dark)");

export function currentTheme(): Theme {
  return effectiveTheme(parseTheme(document.documentElement.dataset.theme), systemQuery().matches);
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    // 저장이 막힌 브라우저(사생활 보호 모드 등): 이 화면에서만 바뀐다
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

/** useSyncExternalStore 구독: 버튼으로 바꿀 때·기기 설정이 바뀔 때. */
export function subscribeTheme(onChange: () => void) {
  const query = systemQuery();
  window.addEventListener(CHANGE_EVENT, onChange);
  query.addEventListener("change", onChange);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    query.removeEventListener("change", onChange);
  };
}
