"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { api, AUTH_EXPIRED_EVENT, errorMessage, type Me } from "@/lib/api";
import { activeGroup, activeHref, visibleMenu, type MenuGroup } from "@/lib/menu";
import { useUi } from "@/lib/uiStore";

import { NotificationBell } from "./NotificationBell";

/**
 * 로그인한 화면의 틀: 위 막대(브랜드·메뉴 버튼·알림·사용자·로그아웃) + 계층형 메뉴.
 * 데스크톱(≥ 900px)은 접고 펼치는 왼쪽 사이드바, 모바일은 서랍. 서랍을 열면 첫 메뉴로 초점을 옮기고,
 * Esc·바깥 누르기·메뉴 선택으로 닫으면 메뉴 버튼으로 초점을 돌려준다. 노출은 화면 편의이고 판단은 서버가 한다.
 */
export function Shell({ me, children }: { me: Me; children: ReactNode }) {
  const pathname = usePathname();
  const menu = visibleMenu(me);
  const drawerOpen = useUi((s) => s.drawerOpen);
  const setDrawerOpen = useUi((s) => s.setDrawerOpen);
  const collapsed = useUi((s) => s.sidebarCollapsed);
  const setCollapsed = useUi((s) => s.setSidebarCollapsed);
  const reset = useUi((s) => s.reset);
  const menuButton = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLDivElement>(null);
  const lastUser = useRef(me.id);

  // 계정이 바뀌거나 로그인이 풀리면 화면 전역 상태를 비운다
  useEffect(() => {
    if (lastUser.current !== me.id) {
      lastUser.current = me.id;
      reset();
    }
  }, [me.id, reset]);
  useEffect(() => {
    window.addEventListener(AUTH_EXPIRED_EVENT, reset);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, reset);
  }, [reset]);

  const closeDrawer = (restoreFocus: boolean) => {
    setDrawerOpen(false);
    if (restoreFocus) requestAnimationFrame(() => menuButton.current?.focus());
  };
  useEffect(() => {
    if (!drawerOpen) return;
    drawer.current?.querySelector<HTMLElement>("a, button")?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && closeDrawer(true);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // closeDrawer 는 store 함수만 쓴다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drawerOpen]);

  return (
    <div className={collapsed ? "shell collapsed" : "shell"}>
      <TopBar me={me} menuButton={menuButton} drawerOpen={drawerOpen} onMenu={() => setDrawerOpen(!drawerOpen)} />
      <aside className="sidebar" aria-label="사이드 메뉴">
        <button type="button" className="sidebar-toggle" aria-expanded={!collapsed} onClick={() => setCollapsed(!collapsed)}>
          {collapsed ? "메뉴 펼치기" : "메뉴 접기"}
        </button>
        {!collapsed && <MenuTree menu={menu} pathname={pathname} label="주 메뉴" />}
      </aside>
      {drawerOpen && (
        <div className="drawer-backdrop" onClick={() => closeDrawer(true)}>
          <div ref={drawer} className="drawer" role="dialog" aria-modal="true" aria-label="메뉴" onClick={(e) => e.stopPropagation()}>
            <button type="button" className="secondary drawer-close" onClick={() => closeDrawer(true)}>
              닫기
            </button>
            <MenuTree menu={menu} pathname={pathname} label="주 메뉴" onSelect={() => closeDrawer(false)} />
          </div>
        </div>
      )}
      <main className="container" inert={drawerOpen || undefined}>
        {children}
      </main>
      <Toaster />
    </div>
  );
}

function MenuTree({ menu, pathname, label, onSelect }: { menu: MenuGroup[]; pathname: string; label: string; onSelect?: () => void }) {
  const current = activeHref(menu, pathname);
  const currentGroup = activeGroup(menu, pathname);
  // 현재 화면이 속한 묶음은 펼쳐 두고, 나머지는 사용자가 연다
  const [open, setOpen] = useState<Record<string, boolean>>({});
  return (
    <nav className="menu" aria-label={label}>
      {menu.map((g) => {
        const expanded = open[g.id] ?? g.id === currentGroup;
        const listId = `menu-${label === "주 메뉴" ? "" : "d-"}${g.id}`;
        return (
          <div key={g.id} className="menu-group">
            <button type="button" className="menu-group-toggle" aria-expanded={expanded} aria-controls={listId}
              onClick={() => setOpen({ ...open, [g.id]: !expanded })}>
              {g.label}
              <span className="chevron" aria-hidden="true">
                ▸
              </span>
            </button>
            {expanded && (
              <ul id={listId}>
                {g.items.map((i) => (
                  <li key={i.href}>
                    <Link href={i.href} aria-current={i.href === current ? "page" : undefined} onClick={onSelect}>
                      {i.label}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}
    </nav>
  );
}

function TopBar({ me, menuButton, drawerOpen, onMenu }: { me: Me; menuButton: React.RefObject<HTMLButtonElement | null>; drawerOpen: boolean; onMenu: () => void }) {
  const router = useRouter();
  const reset = useUi((s) => s.reset);
  const toast = useUi((s) => s.toast);

  async function logout() {
    try {
      await api("/api/auth/logout", { method: "POST" });
      reset();
      router.replace("/login");
    } catch (e) {
      toast(errorMessage(e), "error");
    }
  }

  return (
    <header className="header topbar">
      <button ref={menuButton} type="button" className="secondary menu-button" aria-expanded={drawerOpen} aria-haspopup="dialog" onClick={onMenu}>
        메뉴
      </button>
      <Link href="/" className="brand">
        다음자리
      </Link>
      <span className="spacer" />
      <span className="who">{me.displayName} 님</span>
      <NotificationBell />
      <button type="button" className="secondary" onClick={logout}>
        로그아웃
      </button>
    </header>
  );
}

/** 전역 토스트: 화면 읽기 프로그램에도 알린다. 6초 뒤 사라진다. */
function Toaster() {
  const toasts = useUi((s) => s.toasts);
  const dismiss = useUi((s) => s.dismiss);
  useEffect(() => {
    if (toasts.length === 0) return;
    const timer = setTimeout(() => dismiss(toasts[0].id), 6000);
    return () => clearTimeout(timer);
  }, [toasts, dismiss]);
  // 비어 있을 때는 그리지 않는다 (화면마다 있는 다른 role=status 안내와 겹치지 않게)
  if (toasts.length === 0) return null;
  return (
    <div className="toaster" role="status" aria-live="polite">
      {toasts.map((t) => (
        <p key={t.id} className={`toast ${t.tone}`}>
          {t.text}
          <button type="button" className="secondary" onClick={() => dismiss(t.id)} aria-label="알림 닫기">
            ×
          </button>
        </p>
      ))}
    </div>
  );
}
