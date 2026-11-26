"use client";

import { createContext, useContext, useState, type ReactNode } from "react";
import { createStore, useStore, type StoreApi } from "zustand";

/**
 * 화면 사이에서 함께 쓰는 UI 상태(메뉴 개폐, 전역 토스트). 서버 데이터는 담지 않는다.
 * Next 서버 렌더에서 사용자끼리 공유되지 않도록 모듈 전역이 아니라 Provider 마다 store 를 새로 만든다.
 * 로그아웃·계정 전환·인증 만료(데모 만료 포함) 때 reset 으로 비운다.
 */
export interface Toast {
  id: number;
  text: string;
  tone: "info" | "error";
}

interface UiState {
  sidebarCollapsed: boolean;
  drawerOpen: boolean;
  toasts: Toast[];
  setSidebarCollapsed: (collapsed: boolean) => void;
  setDrawerOpen: (open: boolean) => void;
  toast: (text: string, tone?: Toast["tone"]) => void;
  dismiss: (id: number) => void;
  reset: () => void;
}

const COLLAPSED_KEY = "daumjari:sidebar-collapsed";
let nextToastId = 1;

function readCollapsed(): boolean {
  try {
    return typeof window !== "undefined" && window.localStorage.getItem(COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

export function createUiStore(): StoreApi<UiState> {
  return createStore<UiState>((set) => ({
    sidebarCollapsed: readCollapsed(),
    drawerOpen: false,
    toasts: [],
    setSidebarCollapsed: (collapsed) => {
      try {
        window.localStorage.setItem(COLLAPSED_KEY, collapsed ? "1" : "0");
      } catch {
        // 저장소를 못 쓰면 이번 방문 동안만 기억한다
      }
      set({ sidebarCollapsed: collapsed });
    },
    setDrawerOpen: (open) => set({ drawerOpen: open }),
    toast: (text, tone = "info") => set((s) => ({ toasts: [...s.toasts, { id: nextToastId++, text, tone }].slice(-3) })),
    dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
    reset: () => set({ drawerOpen: false, toasts: [] }),
  }));
}

const UiStoreContext = createContext<StoreApi<UiState> | null>(null);

export function UiStoreProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createUiStore);
  return <UiStoreContext.Provider value={store}>{children}</UiStoreContext.Provider>;
}

export function useUi<T>(selector: (state: UiState) => T): T {
  const store = useContext(UiStoreContext);
  if (!store) throw new Error("UiStoreProvider 밖에서 useUi 를 썼어요.");
  return useStore(store, selector);
}
