// 계층형 메뉴 설정과 현재 위치 계산. 노출은 화면 편의일 뿐이고 권한 판단은 서버가 다시 한다. 다른 모듈에 기대지 않는다(node --test).

export interface MenuUser {
  role: "OPERATOR" | "PARTICIPANT";
  demo: boolean;
  roles?: ("OPERATOR" | "DEVELOPER")[];
}

export interface MenuItem {
  href: string;
  label: string;
  show?: (u: MenuUser) => boolean;
}

export interface MenuGroup {
  id: string;
  label: string;
  items: MenuItem[];
  show?: (u: MenuUser) => boolean;
}

const member = (u: MenuUser) => !u.demo;
const participant = (u: MenuUser) => u.role === "PARTICIPANT";
const operator = (u: MenuUser) => u.role === "OPERATOR";
const developer = (u: MenuUser) => !u.demo && (u.roles ?? []).includes("DEVELOPER");

/** 아직 없는 화면(모임 채팅·사진첩)은 해당 단계에서 더한다. */
export const MENU: MenuGroup[] = [
  { id: "board", label: "게시판", items: [{ href: "/board", label: "회원게시판" }, { href: "/notices", label: "공지사항" }] },
  {
    id: "club",
    label: "모임",
    items: [
      { href: "/clubs", label: "모임 목록" },
      { href: "/clubs/promo", label: "모임 홍보" },
      { href: "/me/clubs", label: "내 모임", show: member },
    ],
  },
  {
    id: "session",
    label: "회차",
    items: [
      { href: "/sessions", label: "회차 목록" },
      { href: "/me/sessions", label: "내가 만든 회차", show: member },
      { href: "/sessions/new", label: "회차 만들기", show: member },
    ],
  },
  {
    id: "activity",
    label: "내 활동",
    items: [
      { href: "/me/reservations", label: "내 신청", show: participant },
      { href: "/me/favorites", label: "즐겨찾기", show: member },
      { href: "/me/notifications", label: "알림" },
      { href: "/me/feedback", label: "개선 요청", show: member },
    ],
  },
  {
    id: "account",
    label: "내 정보",
    show: member,
    items: [
      { href: "/me/profile", label: "프로필" },
      { href: "/me/accounts", label: "로그인 계정 연결" },
      { href: "/account/withdraw", label: "회원 탈퇴", show: participant },
    ],
  },
  {
    id: "admin",
    label: "관리",
    show: operator,
    items: [
      { href: "/operator", label: "대시보드" },
      { href: "/operator/accounts", label: "계정 관리" },
      { href: "/operator/sessions", label: "전체 회차" },
      { href: "/operator/clubs", label: "모임 승인" },
      { href: "/operator/roles", label: "서비스 권한 관리" },
    ],
  },
  {
    id: "dev",
    label: "개발",
    show: developer,
    items: [
      { href: "/dev/feedback", label: "개선사항 관리" },
      { href: "/dev/errors", label: "에러 로그" },
    ],
  },
];

/** 이 사용자에게 보일 메뉴 (빈 묶음은 빼고). */
export function visibleMenu(user: MenuUser): MenuGroup[] {
  return MENU.filter((g) => !g.show || g.show(user))
    .map((g) => ({ ...g, items: g.items.filter((i) => !i.show || i.show(user)) }))
    .filter((g) => g.items.length > 0);
}

/** 지금 주소에 해당하는 메뉴 항목: 가장 길게 맞는 주소 (예: /clubs/12/board → 모임 목록, /clubs/promo → 모임 홍보). */
export function activeHref(menu: MenuGroup[], pathname: string): string | null {
  let best: string | null = null;
  for (const g of menu) {
    for (const i of g.items) {
      const hit = pathname === i.href || pathname.startsWith(`${i.href}/`);
      if (hit && (!best || i.href.length > best.length)) best = i.href;
    }
  }
  return best;
}

export function activeGroup(menu: MenuGroup[], pathname: string): string | null {
  const href = activeHref(menu, pathname);
  return menu.find((g) => g.items.some((i) => i.href === href))?.id ?? null;
}
