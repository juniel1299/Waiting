// 게시판 응답 계약: README "커뮤니티 1단계". 작성자는 공개 라벨만 온다.
import type { Me } from "@/lib/api";

export type BoardKind = "community" | "notice" | "club-board" | "club-notice";

export interface Post {
  id: number;
  board: "COMMUNITY" | "NOTICE" | "CLUB_BOARD" | "CLUB_NOTICE";
  /** 모임 게시판·모임 공지 글이면 그 모임 */
  clubId: number | null;
  title: string;
  /** 목록에서는 null */
  body: string | null;
  author: { label: string };
  mentions: { id: number; nickname: string }[];
  createdAt: string;
  updatedAt: string | null;
  edited: boolean;
  commentCount: number;
  canEdit: boolean;
  canDelete: boolean;
}

export interface PostPage {
  items: Post[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  canWrite: boolean;
}

export interface BoardInfo {
  path: string;
  title: string;
  listLabel: string;
  /** 목록·작성 API */
  api: string;
}

/** 게시판별 주소·이름. 모임 게시판·모임 공지는 모임 주소 아래에 있다. */
export function boardInfo(kind: BoardKind, clubId?: number | string | null): BoardInfo {
  switch (kind) {
    case "community":
      return { path: "/board", title: "회원 게시판", listLabel: "게시판 목록", api: "/api/boards/community/posts" };
    case "notice":
      return { path: "/notices", title: "공지사항", listLabel: "공지 목록", api: "/api/boards/notice/posts" };
    case "club-board":
      return { path: `/clubs/${clubId}/board`, title: "모임 게시판", listLabel: "모임 게시판 목록", api: `/api/clubs/${clubId}/boards/board/posts` };
    case "club-notice":
      return { path: `/clubs/${clubId}/notices`, title: "모임 공지", listLabel: "모임 공지 목록", api: `/api/clubs/${clubId}/boards/notice/posts` };
  }
}

/** 응답의 board 값 → 화면 종류. */
export function kindOf(board: Post["board"]): BoardKind {
  return board.toLowerCase().replace("_", "-") as BoardKind;
}

/** 화면용 쓰기 가능 여부 (근거는 서버의 canWrite·403). 모임 게시판은 서버가 가입·역할로 판단한다. */
export function canWriteBoard(kind: BoardKind, me: Me | null): boolean {
  if (!me || me.demo) return false;
  if (kind === "club-board" || kind === "club-notice") return true;
  return kind === "community" || me.role === "OPERATOR";
}

const LAST_KEY = "daumjari:last-board-list:";

export function rememberBoardList(kind: BoardKind, href: string, clubId?: number | string | null): void {
  try {
    sessionStorage.setItem(LAST_KEY + kind + (clubId ?? ""), href);
  } catch {
    // 저장소를 못 쓰면 기본 목록으로 돌아간다
  }
}

export function lastBoardList(kind: BoardKind, clubId?: number | string | null): string {
  const base = boardInfo(kind, clubId).path;
  try {
    const href = sessionStorage.getItem(LAST_KEY + kind + (clubId ?? ""));
    return href && (href === base || href.startsWith(`${base}?`)) ? href : base;
  } catch {
    return base;
  }
}
