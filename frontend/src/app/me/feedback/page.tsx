"use client";

import Link from "next/link";
import { useEffect, useId, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import { CATEGORY_LABEL, STATUS_LABEL, type FeedbackCategory, type FeedbackSummary } from "@/lib/feedback";

export default function MyFeedbackPage() {
  return <AuthGate>{(me) => (me.demo ? <p role="alert">체험 계정은 개선 요청을 보낼 수 없어요.</p> : <MyFeedback />)}</AuthGate>;
}

/** 같은 사이트의 직전 화면 경로(질의 없이). 다른 사이트에서 왔으면 남기지 않는다. */
function cameFrom(): string | null {
  try {
    const ref = new URL(document.referrer);
    return ref.origin === window.location.origin ? ref.pathname : null;
  } catch {
    return null;
  }
}

/** 내 활동 > 개선 요청: 보내기 폼과 내가 보낸 요청(상태). 다른 사람의 요청은 보이지 않는다. */
function MyFeedback() {
  const id = useId();
  const [items, setItems] = useState<FeedbackSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [category, setCategory] = useState<FeedbackCategory>("IDEA");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ items: FeedbackSummary[] }>("/api/me/feedback")
      .then((res) => {
        setItems(res.data.items);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [attempt]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api("/api/feedback", { method: "POST", body: { category, title, body, pagePath: cameFrom() } });
      setTitle("");
      setBody("");
      setNotice({ kind: "status", text: "개선 요청을 보냈어요. 처리 상태가 바뀌면 알림으로 알려 드릴게요." });
      setAttempt((a) => a + 1);
    } catch (e) {
      const fields = e instanceof ApiError ? e.fields : undefined;
      setNotice({ kind: "alert", text: fields ? Object.values(fields).join(" ") : errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <>
      <h1>개선 요청</h1>
      <p className="meta">불편한 점이나 바라는 기능을 알려 주세요. 보낸 요청은 나와 개발자만 볼 수 있어요.</p>
      <form className="form narrow" onSubmit={submit}>
        <div className="field">
          <label htmlFor={`${id}-cat`}>종류</label>
          <select id={`${id}-cat`} value={category} onChange={(e) => setCategory(e.target.value as FeedbackCategory)}>
            {(Object.keys(CATEGORY_LABEL) as FeedbackCategory[]).map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-title`}>제목</label>
          <input id={`${id}-title`} value={title} maxLength={100} required onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-body`}>내용</label>
          <textarea id={`${id}-body`} value={body} maxLength={2000} rows={5} required onChange={(e) => setBody(e.target.value)} />
          <span className="meta">{body.length} / 2000 · 비밀번호나 개인 정보는 쓰지 마세요.</span>
        </div>
        <div className="actions">
          <button type="submit" disabled={busy}>
            {busy ? "보내는 중…" : "보내기"}
          </button>
        </div>
      </form>
      {notice && <p role={notice.kind}>{notice.text}</p>}
      <h2>보낸 요청</h2>
      {error ? (
        <>
          <p role="alert">{error}</p>
          <button type="button" onClick={() => setAttempt((a) => a + 1)}>
            다시 시도
          </button>
        </>
      ) : !items ? (
        <p>불러오는 중…</p>
      ) : items.length === 0 ? (
        <p className="meta">아직 보낸 요청이 없어요.</p>
      ) : (
        <ul className="cards">
          {items.map((f) => (
            <li key={f.id} className="card">
              <Link href={`/me/feedback/${f.id}`}>{f.title}</Link>
              <span className="meta">
                {CATEGORY_LABEL[f.category]} · {formatSeoul(f.createdAt)}
              </span>
              <span className={`badge fb-${f.status.toLowerCase()}`}>{STATUS_LABEL[f.status]}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
