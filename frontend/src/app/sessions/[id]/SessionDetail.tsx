"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import {
  api,
  errorMessage,
  formatSeoul,
  myReservationLabel,
  STATUS_LABEL,
  type Reservation,
  type StudySession,
} from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export function SessionDetail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <Detail sessionId={id} />}</AuthGate>;
}

interface Notice {
  kind: "status" | "alert";
  text: string;
}

function Detail({ sessionId }: { sessionId: string }) {
  const [session, setSession] = useState<StudySession | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [connectionLost, setConnectionLost] = useState(false);
  const lastSession = useRef<StudySession | null>(null);
  const requestSeq = useRef(0);

  const fetchSession = useCallback(
    async () => (await api<StudySession>(`/api/sessions/${sessionId}`)).data,
    [sessionId],
  );

  /** 화면 반영. 대기 → 확정으로 바뀐 순간에만 한 번 안내한다 (폴링마다 반복하지 않음). */
  const applySession = useCallback((next: StudySession) => {
    const before = lastSession.current?.myReservation?.status;
    if (before === "WAITING" && next.myReservation?.status === "CONFIRMED") {
      setNotice({ kind: "status", text: "대기 순번이 되어 예약이 확정됐어요." });
    }
    lastSession.current = next;
    setSession(next);
    setConnectionLost(false);
  }, []);

  /** 겹친 조회는 마지막에 시작한 것만 반영한다 (늦게 온 옛 응답이 새 상태를 덮지 않게). */
  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    const next = await fetchSession();
    if (seq === requestSeq.current) {
      applySession(next);
    }
  }, [fetchSession, applySession]);

  const reload = async () => {
    try {
      await load();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
  };

  useEffect(() => {
    fetchSession()
      .then(applySession)
      .catch((e: unknown) => setNotice({ kind: "alert", text: errorMessage(e) }));
  }, [fetchSession, applySession]);

  useVisiblePolling({
    // 폴링 실패는 경고를 반복하지 않고 연결 안내 한 줄로만 보여준다
    refresh: () => void load().catch(() => setConnectionLost(true)),
    onConnectionChange: (online) => setConnectionLost(!online),
  });

  /** 예약·취소 결과와 상관없이 서버 상태를 다시 읽어 화면을 맞춘다. */
  async function run(action: () => Promise<Notice>) {
    setBusy(true);
    try {
      setNotice(await action());
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    await reload();
    setBusy(false);
  }

  const reserve = () =>
    run(async () => {
      const res = await api<Reservation>(`/api/sessions/${sessionId}/reservations`, { method: "POST" });
      if (res.status === 201) {
        return { kind: "status", text: "예약이 확정됐어요." };
      }
      return { kind: "status", text: existingNotice(res.data) };
    });

  const joinWaitlist = () =>
    run(async () => {
      const res = await api<Reservation>(`/api/sessions/${sessionId}/waitlist`, { method: "POST" });
      if (res.status === 201) {
        return { kind: "status", text: `대기 ${res.data.waitingPosition}번으로 신청했어요.` };
      }
      return { kind: "status", text: existingNotice(res.data) };
    });

  const cancel = (reservationId: number, waiting: boolean) =>
    run(async () => {
      await api<Reservation>(`/api/reservations/${reservationId}/cancel`, { method: "POST" });
      return { kind: "status", text: waiting ? "대기를 취소했어요." : "예약을 취소했어요." };
    });

  if (!session) {
    return notice ? <p role="alert">{notice.text}</p> : <p>불러오는 중…</p>;
  }

  const mine = session.myReservation;
  return (
    <>
      <Link href="/sessions" className="link">
        ← 회차 목록
      </Link>
      <h1>{session.title}</h1>
      <dl className="facts">
        <dt>시작</dt>
        <dd>{formatSeoul(session.startsAt)}</dd>
        <dt>장소</dt>
        <dd>{session.location}</dd>
        <dt>잔여석</dt>
        <dd data-testid="remaining">
          {session.remainingSeats} / {session.capacity}
        </dd>
        <dt>상태</dt>
        <dd data-testid="session-status">{STATUS_LABEL[session.status]}</dd>
        <dt>대기 인원</dt>
        <dd data-testid="waiting-count">{session.waitingCount}명</dd>
        <dt>내 예약</dt>
        <dd data-testid="my-reservation">{myReservationLabel(mine)}</dd>
      </dl>
      <div className="actions">
        {mine ? (
          <button
            type="button"
            className="secondary"
            disabled={busy}
            onClick={() => cancel(mine.id, mine.status === "WAITING")}
          >
            {mine.status === "WAITING" ? "대기 취소" : "예약 취소"}
          </button>
        ) : session.status === "FULL" ? (
          <button type="button" disabled={busy} onClick={joinWaitlist}>
            대기 신청
          </button>
        ) : (
          <button type="button" disabled={busy || session.status !== "OPEN"} onClick={reserve}>
            예약하기
          </button>
        )}
      </div>
      {notice && <p role={notice.kind}>{notice.text}</p>}
      {connectionLost && <p className="meta">연결이 끊겼어요. 다시 연결되면 바로 최신 상태로 바꿀게요.</p>}
    </>
  );
}

/** 이미 활성 신청이 있어 서버가 200 으로 기존 신청을 돌려준 경우. */
function existingNotice(existing: Reservation): string {
  return existing.status === "WAITING"
    ? `이미 대기 중이에요 (대기 ${existing.waitingPosition}번).`
    : "이미 예약된 회차예요. 기존 예약을 보여드릴게요.";
}
