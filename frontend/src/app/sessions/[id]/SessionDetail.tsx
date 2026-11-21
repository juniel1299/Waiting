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
import { DemoStartButton } from "@/components/DemoStartButton";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export function SessionDetail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <Detail sessionId={id} demoVisitor={me.demo} />}</AuthGate>;
}

interface Notice {
  kind: "status" | "alert";
  text: string;
}

function Detail({ sessionId, demoVisitor }: { sessionId: string; demoVisitor: boolean }) {
  const [session, setSession] = useState<StudySession | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [connectionLost, setConnectionLost] = useState(false);
  // 숨겨 뒀던 화면이 다시 보일 때 서버 응답 전까지 옛 상태를 현재처럼 보이지 않게 한다
  const [verifying, setVerifying] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
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

  /**
   * 겹친 조회는 마지막에 시작한 것만 반영한다 (늦게 온 옛 응답이 새 상태를 덮지 않게).
   * 첫 조회·복귀 조회·폴링·탭 복귀·재연결 모두 이 함수를 거친다. 지난 조회의 실패도 무시한다.
   */
  const applyIfLatest = useCallback(
    (seq: number, next: StudySession) => {
      if (seq === requestSeq.current) {
        applySession(next);
        setVerifying(false);
        setVerifyError(null);
      }
    },
    [applySession],
  );

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    try {
      applyIfLatest(seq, await fetchSession());
    } catch (e) {
      if (seq === requestSeq.current) throw e;
    }
  }, [fetchSession, applyIfLatest]);

  /** 확인 조회 실패: 이미 보여 준 화면이 있으면 오류·다시 시도를, 처음이면 오류만 보인다. */
  const failVerify = useCallback((e: unknown) => {
    if (lastSession.current) {
      setVerifyError(errorMessage(e));
    } else {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
  }, []);

  const reload = async () => {
    try {
      await load();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
  };

  // cacheComponents 는 떠난 화면을 상태째 숨겨 두고, 다시 보일 때 효과만 다시 실행한다.
  // 숨겨질 때 '확인 필요' 로 표시해 두면, 다시 보인 순간부터 복귀 조회 응답 전까지 옛 상태 대신 확인 중을 보인다.
  useEffect(() => {
    const seq = ++requestSeq.current;
    fetchSession()
      .then((next) => applyIfLatest(seq, next))
      .catch((e: unknown) => {
        if (seq === requestSeq.current) failVerify(e);
      });
    return () => {
      setVerifying(true);
      setVerifyError(null);
      setNotice(null);
    };
  }, [fetchSession, applyIfLatest, failVerify]);

  const retryVerify = () => {
    setVerifyError(null);
    load().catch(failVerify);
  };

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

  const cancelSample = () =>
    run(async () => {
      await api<Reservation>("/api/demo/sample-cancel", { method: "POST" });
      return { kind: "status", text: "샘플 참가자가 예약을 취소했어요." };
    });

  const cancel = (reservationId: number, waiting: boolean) =>
    run(async () => {
      await api<Reservation>(`/api/reservations/${reservationId}/cancel`, { method: "POST" });
      return { kind: "status", text: waiting ? "대기를 취소했어요." : "예약을 취소했어요." };
    });

  if (!session) {
    if (!notice) {
      return <p>불러오는 중…</p>;
    }
    return (
      <>
        <p role="alert">{notice.text}</p>
        {demoVisitor && <DemoStartButton label="새 체험 시작하기" />}
      </>
    );
  }

  const mine = session.myReservation;
  // 확인 전에는 바뀔 수 있는 값(잔여석·상태·대기·내 예약)을 보여주지 않고 버튼을 잠근다
  const checking = verifying || verifyError !== null;
  const live = (value: string) => (checking ? "확인 중…" : value);
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
        <dd data-testid="remaining">{live(`${session.remainingSeats} / ${session.capacity}`)}</dd>
        <dt>상태</dt>
        <dd data-testid="session-status">{live(STATUS_LABEL[session.status])}</dd>
        <dt>대기 인원</dt>
        <dd data-testid="waiting-count">{live(`${session.waitingCount}명`)}</dd>
        <dt>내 예약</dt>
        <dd data-testid="my-reservation">{live(myReservationLabel(mine))}</dd>
      </dl>
      <div className="actions">
        {mine ? (
          <button
            type="button"
            className="secondary"
            disabled={busy || checking}
            onClick={() => cancel(mine.id, mine.status === "WAITING")}
          >
            {mine.status === "WAITING" ? "대기 취소" : "예약 취소"}
          </button>
        ) : session.status === "FULL" ? (
          <button type="button" disabled={busy || checking} onClick={joinWaitlist}>
            대기 신청
          </button>
        ) : (
          <button type="button" disabled={busy || checking || session.status !== "OPEN"} onClick={reserve}>
            예약하기
          </button>
        )}
      </div>
      {session.demo && (
        <section className="card" aria-label="데모 체험 안내">
          <p>
            체험 회차예요. 대기 신청 후 샘플 참가자가 취소하면 대기 순서대로 예약이 확정돼요. 체험 데이터는{" "}
            {formatSeoul(session.demo.expiresAt)}에 삭제돼요.
          </p>
          <div className="actions">
            <button
              type="button"
              className="secondary"
              disabled={busy || checking || !session.demo.sampleReservationActive}
              onClick={cancelSample}
            >
              샘플 참가자 취소
            </button>
            <DemoStartButton label="처음부터 다시 체험" className="secondary" />
          </div>
        </section>
      )}
      {verifying && !verifyError && <p role="status">최신 상태 확인 중…</p>}
      {verifyError && (
        <p role="alert">
          최신 상태를 확인하지 못했어요. {verifyError}{" "}
          <button type="button" className="secondary" onClick={retryVerify}>
            다시 시도
          </button>
        </p>
      )}
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
