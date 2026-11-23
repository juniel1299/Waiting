// 게시판 응답 계약: README "커뮤니티 1단계". 작성자는 공개 라벨만 온다.
import type { Me } from "@/lib/api";

export type BoardKind = "community" | "notice";

export interface Post {
  id: number;
  board: "COMMUNITY" | "NOTICE";
  title: string;
  /** 목록에서는 null */
  body: string | null;
  author: { label: string };
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

/** 게시판별 주소·이름. */
export const BOARDS: Record<BoardKind, { path: string; title: string; listLabel: string }> = {
  community: { path: "/board", title: "회원 게시판", listLabel: "게시판 목록" },
  notice: { path: "/notices", title: "공지사항", listLabel: "공지 목록" },
};

/** 화면용 쓰기 가능 여부 (근거는 서버의 canWrite·403). */
export function canWriteBoard(kind: BoardKind, me: Me | null): boolean {
  if (!me || me.demo) return false;
  return kind === "community" || me.role === "OPERATOR";
}

const LAST_KEY = "daumjari:last-board-list:";

export function rememberBoardList(kind: BoardKind, href: string): void {
  try {
    sessionStorage.setItem(LAST_KEY + kind, href);
  } catch {
    // 저장소를 못 쓰면 기본 목록으로 돌아간다
  }
}

export function lastBoardList(kind: BoardKind): string {
  const base = BOARDS[kind].path;
  try {
    const href = sessionStorage.getItem(LAST_KEY + kind);
    return href && (href === base || href.startsWith(`${base}?`)) ? href : base;
  } catch {
    return base;
  }
}
