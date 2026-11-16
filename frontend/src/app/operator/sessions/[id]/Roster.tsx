"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, type RosterEntry, type StudySession } from "@/lib/api";

export function Roster() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <RosterTable sessionId={id} />}</AuthGate>;
}

interface RosterData {
  entries: RosterEntry[];
  session: StudySession;
}

interface CapacityChangeResult {
  session: StudySession;
  promotedCount: number;
}

/** 역할로 화면을 숨기지 않고 서버 응답(참가자는 403)을 그대로 따른다. */
function RosterTable({ sessionId }: { sessionId: string }) {
  const [data, setData] = useState<RosterData | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchRoster = useCallback(async (): Promise<RosterData> => {
    const [roster, session] = await Promise.all([
      api<RosterEntry[]>(`/api/operator/sessions/${sessionId}/roster`),
      api<StudySession>(`/api/sessions/${sessionId}`),
    ]);
    return { entries: roster.data, session: session.data };
  }, [sessionId]);

  useEffect(() => {
    fetchRoster()
      .then(setData)
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [fetchRoster]);

  const entries = data?.entries ?? null;

  return (
    <>
      <Link href="/operator" className="link">
        ← 회차 관리
      </Link>
      {error && <p role="alert">{error}</p>}
      {!error && entries === null && <p>불러오는 중…</p>}
      {entries && (
        <>
          <h1>확정 명단 · {data?.session.title}</h1>
          {data && (
            <CapacityForm session={data.session} onChanged={() => fetchRoster().then(setData)} />
          )}
          {entries.length === 0 ? (
            <p>확정된 참가자가 없어요.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th scope="col">이름</th>
                  <th scope="col">아이디</th>
                  <th scope="col">예약 시각</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.reservationId}>
                    <td>{e.displayName}</td>
                    <td>{e.loginId}</td>
                    <td>{formatSeoul(e.reservedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}

function CapacityForm({ session, onChanged }: { session: StudySession; onChanged: () => Promise<void> }) {
  const [capacity, setCapacity] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = await api<CapacityChangeResult>(`/api/operator/sessions/${session.id}/capacity`, {
        method: "PATCH",
        body: { capacity: Number(capacity) },
      });
      const promoted = data.promotedCount > 0 ? ` 대기자 ${data.promotedCount}명이 확정됐어요.` : "";
      setNotice({ kind: "status", text: `정원을 ${data.session.capacity}명으로 바꿨어요.${promoted}` });
      setCapacity("");
      await onChanged();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <section className="form">
      <p data-testid="capacity">
        정원 {session.capacity}명 · 확정 {session.confirmedCount}명 · 대기 {session.waitingCount}명
      </p>
      <form onSubmit={submit} className="actions">
        <label>
          새 정원
          <input
            type="number"
            min={1}
            max={1000}
            step={1}
            value={capacity}
            onChange={(e) => setCapacity(e.target.value)}
            required
          />
        </label>
        <button type="submit" disabled={busy || session.status === "STARTED"}>
          정원 변경
        </button>
      </form>
      {session.status === "STARTED" && <p className="meta">시작된 회차는 정원을 바꿀 수 없어요.</p>}
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </section>
  );
}
