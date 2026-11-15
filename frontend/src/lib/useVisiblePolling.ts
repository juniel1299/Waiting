"use client";

import { useEffect, useRef } from "react";

/** 회차 상세 폴링 간격. 빌드 시 NEXT_PUBLIC_SESSION_POLL_MS 로 바꿀 수 있다 (기본 5초). */
export const SESSION_POLL_MS = Number(process.env.NEXT_PUBLIC_SESSION_POLL_MS ?? 5000);

interface PollingHandlers {
  /** 조회 1회. 폴링·탭 복귀·네트워크 복구 때 호출된다. */
  refresh: () => void;
  /** online/offline 이벤트를 알린다. */
  onConnectionChange?: (online: boolean) => void;
}

/**
 * 화면이 보이고 온라인일 때만 intervalMs 마다 refresh 한다.
 * 탭으로 돌아오거나(visibilitychange) 네트워크가 복구되면(online) 즉시 한 번 조회하고 폴링을 다시 시작한다.
 */
export function useVisiblePolling(handlers: PollingHandlers, intervalMs: number = SESSION_POLL_MS) {
  const latest = useRef(handlers);
  useEffect(() => {
    latest.current = handlers;
  });

  useEffect(() => {
    let timer: number | undefined;
    const canPoll = () => document.visibilityState === "visible" && navigator.onLine;
    const stop = () => {
      window.clearInterval(timer);
      timer = undefined;
    };
    const start = () => {
      stop();
      if (canPoll()) {
        timer = window.setInterval(() => latest.current.refresh(), intervalMs);
      }
    };
    const refreshNowAndRestart = () => {
      if (canPoll()) {
        latest.current.refresh();
      }
      start();
    };
    const onOnline = () => {
      latest.current.onConnectionChange?.(true);
      refreshNowAndRestart();
    };
    const onOffline = () => {
      latest.current.onConnectionChange?.(false);
      stop();
    };

    document.addEventListener("visibilitychange", refreshNowAndRestart);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    start();
    return () => {
      document.removeEventListener("visibilitychange", refreshNowAndRestart);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      stop();
    };
  }, [intervalMs]);
}
