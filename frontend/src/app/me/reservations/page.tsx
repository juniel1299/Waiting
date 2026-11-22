"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, type Reservation } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";
import { describeChange, HIGHLIGHT_MS } from "@/lib/reservationChange";

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
  // 숨겨 뒀던 화면이 다시 보일 때 서버 응답 전까지 옛 상태를 현재처럼 보이지 않게 한다 (상세와 같은 방식)
  const [verifying, setVerifying] = useState(false);
  const requestSeq = useRef(0);
  // 다른 사람의 취소로 순번이 앞당겨지거나 확정된 신청: 잠깐 강조하고 안내한다
  const previous = useRef<Map<number, Reservation>>(new Map());
  const [changes, setChanges] = useState<{ ids: number[]; text: string } | null>(null);
  const highlightTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(highlightTimer.current), []);

  const applyIfLatest = useCallback((seq: number, data: Reservation[]) => {
    if (seq === requestSeq.current) {
      const found = data
        .map((r) => ({ r, text: describeChange(previous.current.get(r.id) ?? null, r) }))
        .filter((c): c is { r: Reservation; text: string } => c.text !== null);
      if (found.length > 0) {
        setChanges({ ids: found.map((c) => c.r.id), text: found.map((c) => `${c.r.sessionTitle}: ${c.text}`).join(" ") });
        clearTimeout(highlightTimer.current);
        highlightTimer.current = setTimeout(() => setChanges((c) => c && { ...c, ids: [] }), HIGHLIGHT_MS);
      }
      previous.current = new Map(data.map((r) => [r.id, r]));
      setItems(data);
      setError(null);
      setConnectionLost(false);
      setVerifying(false);
    }
  }, []);

  /** 겹친 조회는 마지막에 시작한 것만 반영한다 (지난 조회의 응답·실패는 무시). */
  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    try {
      applyIfLatest(seq, (await api<Reservation[]>("/api/me/reservations")).data);
    } catch (e) {
      if (seq === requestSeq.current) throw e;
    }
  }, [applyIfLatest]);

  useEffect(() => {
    const seq = ++requestSeq.current;
    api<Reservation[]>("/api/me/reservations")
      .then(({ data }) => applyIfLatest(seq, data))
      .catch((e: unknown) => {
        if (seq === requestSeq.current) setError(errorMessage(e));
      });
    return () => setVerifying(true);
  }, [applyIfLatest]);

  const retry = () => {
    setError(null);
    load().catch((e: unknown) => setError(errorMessage(e)));
  };

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
      {verifying && !error && <p role="status">최신 상태 확인 중…</p>}
      {changes && <p role="status">{changes.text}</p>}
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={retry}>
            다시 시도
          </button>
        </p>
      )}
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
                <span
                  data-testid="reservation-status"
                  data-changed={changes?.ids.includes(r.id) || undefined}
                  className={r.status === "WAITING" ? "badge" : "badge confirmed"}>
                  {verifying || error ? "확인 중…" : statusLabel(r)}
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
