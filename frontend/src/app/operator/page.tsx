"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, seoulLocalToIso, type StudySession } from "@/lib/api";

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
      <CreateSessionForm onCreated={reload} />
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
            <Link href={`/operator/sessions/${s.id}`} className="link">
              명단 보기
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}

function CreateSessionForm({ onCreated }: { onCreated: () => Promise<void> }) {
  const [title, setTitle] = useState("");
  const [startsAt, setStartsAt] = useState("");
  const [location, setLocation] = useState("");
  const [capacity, setCapacity] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api("/api/operator/sessions", {
        method: "POST",
        body: { title, startsAt: seoulLocalToIso(startsAt), location, capacity: Number(capacity) },
      });
      setNotice({ kind: "status", text: "회차를 등록했어요." });
      setTitle("");
      setStartsAt("");
      setLocation("");
      setCapacity("");
      await onCreated();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <form onSubmit={submit} className="form">
      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} required />
      </label>
      <label>
        시작 일시 (서울)
        <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
      </label>
      <label>
        장소
        <input value={location} onChange={(e) => setLocation(e.target.value)} maxLength={200} required />
      </label>
      <label>
        정원
        <input
          type="number"
          min={1}
          max={1000}
          value={capacity}
          onChange={(e) => setCapacity(e.target.value)}
          required
        />
      </label>
      <button type="submit" disabled={busy}>
        회차 등록
      </button>
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </form>
  );
}
