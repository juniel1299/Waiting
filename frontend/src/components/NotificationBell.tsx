"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";
import { NOTIFICATION_POLL_MS, NOTIFICATIONS_CHANGED, unreadLabel } from "@/lib/notification";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

/**
 * 머리글 알림 버튼과 읽지 않은 수. 보이는 동안 30초마다, 탭 복귀·재연결 때는 즉시 다시 읽는다.
 * 앞선 요청이 끝나기 전에는 새 요청을 보내지 않는다 (요청 중복 없음). 실패하면 마지막 숫자를 그대로 둔다.
 */
export function NotificationBell() {
  const [count, setCount] = useState<number | null>(null);
  const inFlight = useRef(false);

  const refresh = useCallback(() => {
    if (inFlight.current) return;
    inFlight.current = true;
    api<{ unreadCount: number }>("/api/me/notifications/unread-count")
      .then((res) => setCount(res.data.unreadCount))
      .catch(() => undefined)
      .finally(() => {
        inFlight.current = false;
      });
  }, []);

  useEffect(() => {
    refresh();
    window.addEventListener(NOTIFICATIONS_CHANGED, refresh);
    return () => window.removeEventListener(NOTIFICATIONS_CHANGED, refresh);
  }, [refresh]);

  useVisiblePolling({ refresh }, NOTIFICATION_POLL_MS);

  const unread = count ?? 0;
  return (
    <Link href="/me/notifications" className="link bell" aria-label={unreadLabel(unread)} data-testid="notification-bell">
      알림
      {unread > 0 && (
        <span className="bell-count" aria-hidden="true">
          {unread > 99 ? "99+" : unread}
        </span>
      )}
    </Link>
  );
}
