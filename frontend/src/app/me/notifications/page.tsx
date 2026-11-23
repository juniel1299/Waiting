"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import {
  NOTIFICATION_POLL_MS,
  NOTIFICATIONS_CHANGED,
  notificationText,
  type NotificationItem,
  type NotificationPage,
} from "@/lib/notification";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export default function NotificationsPage() {
  return <AuthGate>{() => <Inbox />}</AuthGate>;
}

const announceChanged = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));

/**
 * 내 알림함: 최신 50건. 읽음 상태는 색과 글자(읽지 않음·읽음)로 함께 보인다.
 * '열기'는 서버가 지금 볼 수 있는지 다시 확인한 뒤 주소를 주고, 그때 읽음으로 바뀐다.
 */
function Inbox() {
  const router = useRouter();
  const [page, setPage] = useState<NotificationPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const requestSeq = useRef(0);

  /** 겹친 조회는 마지막에 시작한 것만 반영한다. */
  const fetchLatest = useCallback(() => {
    const seq = ++requestSeq.current;
    return api<NotificationPage>("/api/me/notifications").then(
      ({ data }) => {
        if (seq === requestSeq.current) {
          setPage(data);
          setError(null);
        }
      },
      (e: unknown) => {
        if (seq === requestSeq.current) setError(errorMessage(e));
      },
    );
  }, []);
  const load = fetchLatest;

  useEffect(() => {
    fetchLatest();
  }, [fetchLatest]);
  useVisiblePolling({ refresh: () => void load() }, NOTIFICATION_POLL_MS);

  async function act(run: () => Promise<unknown>, done: string | null) {
    setBusy(true);
    try {
      await run();
      if (done) setStatus(done);
    } catch (e) {
      setStatus(errorMessage(e));
    } finally {
      setBusy(false);
      announceChanged();
      await load();
    }
  }

  async function open(n: NotificationItem) {
    setBusy(true);
    try {
      const { data } = await api<{ href: string }>(`/api/me/notifications/${n.id}/open`, { method: "POST" });
      announceChanged();
      router.push(data.href);
    } catch (e) {
      // 대상이 지워졌거나 더는 볼 수 없다: 이유를 알리고 목록을 다시 읽는다
      setStatus(errorMessage(e));
      setBusy(false);
      await load();
    }
  }

  return (
    <>
      <h1>알림</h1>
      <p role="status" className="sr-only-when-empty">
        {status}
      </p>
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={() => void load()}>
            다시 시도
          </button>
        </p>
      )}
      {!page && !error && <p>불러오는 중…</p>}
      {page && (
        <>
          <p className="inbox-head">
            <span>읽지 않은 알림 {page.unreadCount}개</span>
            <button
              type="button"
              className="secondary"
              disabled={busy || page.unreadCount === 0}
              onClick={() =>
                void act(() => api("/api/me/notifications/read-all", { method: "POST" }), "모든 알림을 읽음으로 표시했어요.")
              }>
              모두 읽음
            </button>
          </p>
          {page.items.length === 0 ? (
            <p>아직 알림이 없어요. 대기하던 회차가 확정되거나 누군가 회원님을 언급하면 여기에 보여요.</p>
          ) : (
            <ul className="inbox">
              {page.items.map((n) => (
                <li key={n.id} className={n.read ? "notice read" : "notice unread"} data-testid="notification">
                  <span className={n.read ? "badge" : "badge unread"}>{n.read ? "읽음" : "읽지 않음"}</span>
                  <span className="notice-text">{notificationText(n)}</span>
                  <span className="meta">{formatSeoul(n.createdAt)}</span>
                  <span className="notice-actions">
                    <button type="button" disabled={busy} onClick={() => void open(n)}>
                      열기
                    </button>
                    {!n.read && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() =>
                          void act(() => api(`/api/me/notifications/${n.id}/read`, { method: "POST" }), "읽음으로 표시했어요.")
                        }>
                        읽음 표시
                      </button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}
