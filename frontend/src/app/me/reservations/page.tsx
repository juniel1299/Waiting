"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, type Reservation } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export default function MyReservationsPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "PARTICIPANT" ? <MyReservations /> : <p>운영자 계정에는 신청 내역이 없어요.</p>)}
    </AuthGate>
  );
}

function statusLabel(r: Reservation): string {
  return r.status === "WAITING" ? `대기 ${r.waitingPosition}번` : "예약 확정";
}

/**
 * 내 활성 신청(확정·대기)을 시작 시각 순으로 모아 보여준다. 서버가 세션 계정의 신청만 돌려준다.
 * 상세 화면과 같은 폴링(보이는 동안 5초, 탭 복귀·재연결 즉시)으로 승격 같은 변화를 따라간다.
 */
function MyReservations() {
  const [items, setItems] = useState<Reservation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [connectionLost, setConnectionLost] = useState(false);
  const requestSeq = useRef(0);

  /** 겹친 조회는 마지막에 시작한 것만 반영한다. */
  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    const { data } = await api<Reservation[]>("/api/me/reservations");
    if (seq === requestSeq.current) {
      setItems(data);
      setError(null);
      setConnectionLost(false);
    }
  }, []);

  useEffect(() => {
    load().catch((e: unknown) => setError(errorMessage(e)));
  }, [load]);

  useVisiblePolling({
    refresh: () => void load().catch(() => setConnectionLost(true)),
    onConnectionChange: (online) => setConnectionLost(!online),
  });

  const active = items
    ?.filter((r) => r.status !== "CANCELLED")
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.id - b.id);

  return (
    <>
      <h1>내 신청</h1>
      {error && <p role="alert">{error}</p>}
      {active === undefined && !error && <p>불러오는 중…</p>}
      {active?.length === 0 && (
        <p>
          아직 신청한 회차가 없어요.{" "}
          <Link href="/sessions" className="link">
            회차 목록 보기
          </Link>
        </p>
      )}
      {active && active.length > 0 && (
        <ul className="cards">
          {active.map((r) => (
            <li key={r.id} className="card">
              <Link href={`/sessions/${r.sessionId}`} className="title">
                {r.sessionTitle}
              </Link>
              <span className="meta">
                {formatSeoul(r.startsAt)} · {r.location}
              </span>
              <span>
                <span data-testid="reservation-status" className={r.status === "WAITING" ? "badge" : "badge confirmed"}>
                  {statusLabel(r)}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {connectionLost && <p className="meta">연결이 끊겼어요. 다시 연결되면 바로 최신 상태로 바꿀게요.</p>}
    </>
  );
}
