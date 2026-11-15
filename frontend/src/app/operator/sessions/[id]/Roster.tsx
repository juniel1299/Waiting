"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, type RosterEntry, type StudySession } from "@/lib/api";

export function Roster() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <RosterTable sessionId={id} />}</AuthGate>;
}

/** 역할로 화면을 숨기지 않고 서버 응답(참가자는 403)을 그대로 따른다. */
function RosterTable({ sessionId }: { sessionId: string }) {
  const [title, setTitle] = useState<string | null>(null);
  const [entries, setEntries] = useState<RosterEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api<RosterEntry[]>(`/api/operator/sessions/${sessionId}/roster`),
      api<StudySession>(`/api/sessions/${sessionId}`),
    ])
      .then(([roster, session]) => {
        setEntries(roster.data);
        setTitle(session.data.title);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [sessionId]);

  return (
    <>
      <Link href="/operator" className="link">
        ← 회차 관리
      </Link>
      {error && <p role="alert">{error}</p>}
      {!error && entries === null && <p>불러오는 중…</p>}
      {entries && (
        <>
          <h1>확정 명단 · {title}</h1>
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
