"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, myReservationLabel, STATUS_LABEL, type StudySession } from "@/lib/api";

export default function SessionsPage() {
  return <AuthGate>{() => <SessionList />}</AuthGate>;
}

function SessionList() {
  const [sessions, setSessions] = useState<StudySession[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<StudySession[]>("/api/sessions")
      .then((res) => setSessions(res.data))
      .catch((e: unknown) => setError(errorMessage(e)));
  }, []);

  return (
    <>
      <h1>회차 목록</h1>
      {error && <p role="alert">{error}</p>}
      {sessions === null && !error && <p>불러오는 중…</p>}
      {sessions?.length === 0 && <p>등록된 회차가 없어요.</p>}
      <ul className="cards">
        {sessions?.map((s) => (
          <li key={s.id} className="card">
            <Link href={`/sessions/${s.id}`} className="title">
              {s.title}
            </Link>
            <span className="meta">
              {formatSeoul(s.startsAt)} · {s.location}
            </span>
            <span>
              잔여 {s.remainingSeats}석 / 정원 {s.capacity} <span className="badge">{STATUS_LABEL[s.status]}</span>
              {s.waitingCount > 0 && <span className="badge">대기 {s.waitingCount}명</span>}
              {s.myReservation && <span className="badge">내 예약: {myReservationLabel(s.myReservation)}</span>}
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}
