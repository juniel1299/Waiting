"use client";

import { useState, type FormEvent } from "react";

import { api, errorMessage, isoToSeoulLocal, seoulLocalToIso, type StudySession } from "@/lib/api";

interface SessionFormProps {
  submitLabel: string;
  /** 있으면 수정(제목·시작·장소, PATCH). 정원은 회차 관리 화면에서 바꾼다 (대기자 승격이 따르므로). */
  session?: StudySession;
  onSaved: (session: StudySession) => unknown;
}

/** 회차 만들기·수정. 생성자는 서버가 로그인 세션에서 정한다. */
export function SessionForm({ submitLabel, session, onSaved }: SessionFormProps) {
  const editing = session !== undefined;
  const [title, setTitle] = useState(session?.title ?? "");
  const [startsAt, setStartsAt] = useState(session ? isoToSeoulLocal(session.startsAt) : "");
  const [location, setLocation] = useState(session?.location ?? "");
  const [capacity, setCapacity] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const body = { title, startsAt: seoulLocalToIso(startsAt), location };
      const { data } = editing
        ? await api<StudySession>(`/api/sessions/${session.id}`, { method: "PATCH", body })
        : await api<StudySession>("/api/sessions", { method: "POST", body: { ...body, capacity: Number(capacity) } });
      setNotice({ kind: "status", text: editing ? "회차를 수정했어요." : "회차를 등록했어요." });
      if (!editing) {
        setTitle("");
        setStartsAt("");
        setLocation("");
        setCapacity("");
      }
      await onSaved(data);
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
      {!editing && (
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
      )}
      <button type="submit" disabled={busy}>
        {submitLabel}
      </button>
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </form>
  );
}
