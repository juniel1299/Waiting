// 회차 검색 조건 (전체 회차·내가 만든 회차 공통). 주소가 조건의 원본이라 새로고침·상세 왕복·공유에도 그대로다.
// 서버 계약: README "회차 검색" (GET /api/sessions/search). 다른 모듈에 기대지 않아 node --test 로 바로 검증한다.

export const FIELDS = [
  ["ALL", "제목+장소"],
  ["TITLE", "제목"],
  ["LOCATION", "장소"],
] as const;

export const STATUSES = [
  ["UPCOMING", "시작 전 전체"],
  ["OPEN", "예약 가능"],
  ["WAITLIST", "마감(대기 가능)"],
  ["ENDED", "시작된 회차"],
  ["ALL", "전체"],
] as const;

export const SORTS = [
  ["SOON", "시작 임박순"],
  ["LATE", "시작 늦은순"],
  ["NEWEST", "최근 등록순"],
  ["OLDEST", "먼저 등록순"],
  ["SEATS", "남은 자리 많은순"],
  ["POPULAR", "확정 인원 많은순"],
  ["TITLE", "제목순"],
] as const;

export type Field = (typeof FIELDS)[number][0];
export type StatusFilter = (typeof STATUSES)[number][0];
export type Sort = (typeof SORTS)[number][0];
export type Scope = "all" | "mine";

export interface SearchState {
  q: string;
  field: Field;
  /** 서울 기준 날짜 yyyy-MM-dd, 종료일 포함 */
  from: string;
  to: string;
  status: StatusFilter;
  sort: Sort;
  page: number;
}

export const PAGE_SIZE = 10;

export const DEFAULT_SEARCH: SearchState = Object.freeze({
  q: "",
  field: "ALL",
  from: "",
  to: "",
  status: "UPCOMING",
  sort: "SOON",
  page: 1,
}) as SearchState;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function option<T extends string>(options: readonly (readonly [T, string])[], raw: string | null, fallback: T): T {
  return options.find(([value]) => value === raw)?.[0] ?? fallback;
}

/** 주소의 조건. 모르는 값은 기본값으로 둔다 (주소를 손으로 고쳐도 화면이 깨지지 않게). */
export function parseSearch(params: URLSearchParams): SearchState {
  const page = Number(params.get("page"));
  const date = (key: string) => {
    const value = params.get(key) ?? "";
    return DATE.test(value) ? value : "";
  };
  return {
    q: (params.get("q") ?? "").trim(),
    field: option(FIELDS, params.get("field"), DEFAULT_SEARCH.field),
    from: date("from"),
    to: date("to"),
    status: option(STATUSES, params.get("status"), DEFAULT_SEARCH.status),
    sort: option(SORTS, params.get("sort"), DEFAULT_SEARCH.sort),
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  };
}

/** 기본값이 아닌 조건만 담은 목록 주소. */
export function searchHref(path: string, state: SearchState): string {
  const params = new URLSearchParams();
  for (const key of ["q", "field", "from", "to", "status", "sort", "page"] as const) {
    if (state[key] !== DEFAULT_SEARCH[key]) params.set(key, String(state[key]));
  }
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

/** 조건을 바꾸면 1쪽부터. 쪽만 바꿀 때는 그 쪽으로. 원래 조건은 그대로 둔다. */
export function withChange(state: SearchState, change: Partial<SearchState>): SearchState {
  return { ...state, page: 1, ...change };
}

export function apiQuery(scope: Scope, state: SearchState): string {
  const params = new URLSearchParams({ scope });
  if (state.q) params.set("q", state.q);
  params.set("field", state.field);
  if (state.from) params.set("from", state.from);
  if (state.to) params.set("to", state.to);
  params.set("status", state.status);
  params.set("sort", state.sort);
  params.set("page", String(state.page));
  params.set("size", String(PAGE_SIZE));
  return params.toString();
}

/** 현재 쪽 주변 최대 width 개 쪽 번호. */
export function pageWindow(page: number, totalPages: number, width = 5): number[] {
  const start = Math.max(1, Math.min(page - Math.floor(width / 2), totalPages - width + 1));
  const end = Math.min(totalPages, start + width - 1);
  return Array.from({ length: Math.max(0, end - start + 1) }, (_, i) => start + i);
}

const LAST_LIST_KEY = "daumjari:last-list";

/** 상세의 '← 목록' 이 검색 조건을 그대로 둔 목록으로 돌아가도록 마지막 목록 주소를 기억한다 (탭 단위). */
export function rememberListHref(href: string): void {
  try {
    sessionStorage.setItem(LAST_LIST_KEY, href);
  } catch {
    // 저장소를 못 쓰면(사생활 보호 모드 등) 기본 목록으로 돌아간다
  }
}

export function lastListHref(): string {
  try {
    const href = sessionStorage.getItem(LAST_LIST_KEY);
    return href && /^\/(sessions|me\/sessions)(\?|$)/.test(href) ? href : "/sessions";
  } catch {
    return "/sessions";
  }
}
