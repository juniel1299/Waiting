"use client";

import { useState, type FormEvent } from "react";

import { api, errorMessage, seoulLocalToIso, type StudySession } from "@/lib/api";

/** 회차 만들기. 생성자는 서버가 로그인 세션에서 정한다. */
export function CreateSessionForm({
  submitLabel,
  onCreated,
}: {
  submitLabel: string;
  onCreated: (session: StudySession) => unknown;
}) {
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
      const { data } = await api<StudySession>("/api/sessions", {
        method: "POST",
        body: { title, startsAt: seoulLocalToIso(startsAt), location, capacity: Number(capacity) },
      });
      setNotice({ kind: "status", text: "회차를 등록했어요." });
      setTitle("");
      setStartsAt("");
      setLocation("");
      setCapacity("");
      await onCreated(data);
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
        {submitLabel}
      </button>
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </form>
  );
}
