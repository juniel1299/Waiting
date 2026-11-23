// 게시판 검색 조건 (회원 게시판·서비스 공지 공통). 회차 검색처럼 주소가 조건의 원본이다. 다른 모듈에 기대지 않는다(node --test).

export const BOARD_FIELDS = [
  ["ALL", "제목+본문"],
  ["TITLE", "제목"],
  ["BODY", "본문"],
] as const;

export type BoardField = (typeof BOARD_FIELDS)[number][0];

export interface BoardSearch {
  q: string;
  field: BoardField;
  page: number;
}

export const BOARD_PAGE_SIZE = 20;

export const BOARD_DEFAULT: BoardSearch = Object.freeze({ q: "", field: "ALL", page: 1 }) as BoardSearch;

export function parseBoardSearch(params: URLSearchParams): BoardSearch {
  const page = Number(params.get("page"));
  const field = BOARD_FIELDS.find(([value]) => value === params.get("field"))?.[0] ?? BOARD_DEFAULT.field;
  return {
    q: (params.get("q") ?? "").trim(),
    field,
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  };
}

/** 기본값이 아닌 조건만 담은 목록 주소. */
export function boardHref(path: string, search: BoardSearch): string {
  const params = new URLSearchParams();
  for (const key of ["q", "field", "page"] as const) {
    if (search[key] !== BOARD_DEFAULT[key]) params.set(key, String(search[key]));
  }
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

export function boardApiQuery(search: BoardSearch): string {
  const params = new URLSearchParams();
  if (search.q) params.set("q", search.q);
  params.set("field", search.field);
  params.set("page", String(search.page));
  params.set("size", String(BOARD_PAGE_SIZE));
  return params.toString();
}
