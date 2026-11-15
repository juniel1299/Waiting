"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, STATUS_LABEL, type Reservation, type StudySession } from "@/lib/api";

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

  const fetchSession = useCallback(
    async () => (await api<StudySession>(`/api/sessions/${sessionId}`)).data,
    [sessionId],
  );

  const reload = async () => {
    try {
      setSession(await fetchSession());
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
  };

  useEffect(() => {
    fetchSession()
      .then(setSession)
      .catch((e: unknown) => setNotice({ kind: "alert", text: errorMessage(e) }));
  }, [fetchSession]);

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
      return { kind: "status", text: res.status === 201 ? "예약이 확정됐어요." : "이미 예약된 회차예요. 기존 예약을 보여드릴게요." };
    });

  const cancel = (reservationId: number) =>
    run(async () => {
      await api<Reservation>(`/api/reservations/${reservationId}/cancel`, { method: "POST" });
      return { kind: "status", text: "예약을 취소했어요." };
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
        <dt>내 예약</dt>
        <dd data-testid="my-reservation">{mine ? "확정" : "예약 없음"}</dd>
      </dl>
      <div className="actions">
        {mine ? (
          <button type="button" className="secondary" disabled={busy} onClick={() => cancel(mine.id)}>
            예약 취소
          </button>
        ) : (
          <button type="button" disabled={busy || session.status !== "OPEN"} onClick={reserve}>
            예약하기
          </button>
        )}
      </div>
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </>
  );
}
