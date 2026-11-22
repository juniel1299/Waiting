"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, type RosterEntry, type StudySession } from "@/lib/api";

/** 회차 관리: 생성자는 명단·정원·삭제, 운영자는 명단·삭제. 권한은 서버가 정하고(다른 회원은 403) 화면은 응답을 따른다. */
export function Manage() {
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
      api<RosterEntry[]>(`/api/sessions/${sessionId}/roster`),
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
      <Link href={`/sessions/${sessionId}`} className="link">
        ← 회차 상세
      </Link>
      {error && <p role="alert">{error}</p>}
      {!error && entries === null && <p>불러오는 중…</p>}
      {entries && (
        <>
          <h1>회차 관리 · {data?.session.title}</h1>
          {data?.session.canManage && (
            <CapacityForm session={data.session} onChanged={() => fetchRoster().then(setData)} />
          )}
          <h2>확정 명단</h2>
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
          {data?.session.canDelete && <DeleteSession session={data.session} />}
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
      const { data } = await api<CapacityChangeResult>(`/api/sessions/${session.id}/capacity`, {
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

/** 물리 삭제. 연결된 신청(확정·대기·취소)도 함께 지워지고 복구할 수 없다고 확인받는다. */
function DeleteSession({ session }: { session: StudySession }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    const message =
      `'${session.title}' 회차를 삭제할까요?\n` +
      `확정 ${session.confirmedCount}명·대기 ${session.waitingCount}명을 포함한 이 회차의 신청이 모두 함께 삭제되고, ` +
      "대기자 승격 없이 끝나며 복구할 수 없어요.";
    if (!window.confirm(message)) return;
    setBusy(true);
    try {
      await api(`/api/sessions/${session.id}`, { method: "DELETE" });
      router.replace("/sessions");
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <section className="form" aria-label="회차 삭제">
      <button type="button" className="danger" onClick={remove} disabled={busy}>
        회차 삭제
      </button>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
