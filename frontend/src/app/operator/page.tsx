"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { CreateSessionForm } from "@/components/CreateSessionForm";
import { api, errorMessage, formatSeoul, type StudySession } from "@/lib/api";

export default function OperatorPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "OPERATOR" ? <OperatorHome /> : <p role="alert">권한이 없습니다.</p>)}
    </AuthGate>
  );
}

const loadSessions = async () => (await api<StudySession[]>("/api/sessions")).data;

function OperatorHome() {
  const [sessions, setSessions] = useState<StudySession[]>([]);
  const [listError, setListError] = useState<string | null>(null);

  const reload = useCallback(
    () =>
      loadSessions()
        .then(setSessions)
        .catch((e: unknown) => setListError(errorMessage(e))),
    [],
  );

  useEffect(() => {
    loadSessions()
      .then(setSessions)
      .catch((e: unknown) => setListError(errorMessage(e)));
  }, []);

  return (
    <>
      <h1>회차 관리</h1>
      <CreateSessionForm submitLabel="회차 등록" onCreated={() => reload()} />
      <h2>등록된 회차</h2>
      {listError && <p role="alert">{listError}</p>}
      <ul className="cards">
        {sessions.map((s) => (
          <li key={s.id} className="card">
            <span className="title">{s.title}</span>
            <span className="meta">
              {formatSeoul(s.startsAt)} · {s.location}
            </span>
            <span>
              확정 {s.confirmedCount} / {s.capacity}
            </span>
            <Link href={`/sessions/${s.id}/manage`} className="link">
              명단 보기
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
