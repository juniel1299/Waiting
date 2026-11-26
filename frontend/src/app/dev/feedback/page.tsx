"use client";

import { useCallback, useEffect, useId, useState, type FormEvent } from "react";

import { DevGate } from "@/components/DevGate";
import { FeedbackView } from "@/components/FeedbackView";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import {
  CATEGORY_LABEL,
  NEEDS_REASON,
  STATUS_LABEL,
  type FeedbackCategory,
  type FeedbackDetail,
  type FeedbackStatus,
  type FeedbackSummary,
} from "@/lib/feedback";

export default function DevFeedbackPage() {
  return <DevGate>{() => <DevFeedback />}</DevGate>;
}

/** 개발 > 개선사항 관리: 전체 검색·필터, 하나를 골라 상태(사유)·처리 내용·PR 링크를 바꾼다. */
function DevFeedback() {
  const id = useId();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"" | FeedbackStatus>("");
  const [category, setCategory] = useState<"" | FeedbackCategory>("");
  const [query, setQuery] = useState({ q: "", status: "", category: "" });
  const [page, setPage] = useState<{ items: FeedbackSummary[]; totalElements: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<FeedbackDetail | null>(null);

  const load = useCallback(() => {
    const params = new URLSearchParams(Object.entries(query).filter(([, v]) => v));
    api<{ items: FeedbackSummary[]; totalElements: number }>(`/api/dev/feedback?${params}`)
      .then((res) => {
        setPage(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [query]);
  useEffect(load, [load]);

  function search(event: FormEvent) {
    event.preventDefault();
    setQuery({ q: q.trim(), status, category });
  }

  async function open(feedbackId: number) {
    try {
      setSelected((await api<FeedbackDetail>(`/api/feedback/${feedbackId}`)).data);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <>
      <h1>개선사항 관리</h1>
      <p className="meta">회원이 보낸 요청 전체. 검증 요청·보류로 바꿀 때는 사유가 필요하고, 바꾸면 작성자에게 알림이 가요.</p>
      <form className="filters" onSubmit={search}>
        <div className="field">
          <label htmlFor={`${id}-q`}>검색어</label>
          <input id={`${id}-q`} value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-st`}>상태</label>
          <select id={`${id}-st`} value={status} onChange={(e) => setStatus(e.target.value as "" | FeedbackStatus)}>
            <option value="">전체</option>
            {(Object.keys(STATUS_LABEL) as FeedbackStatus[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-cat`}>종류</label>
          <select id={`${id}-cat`} value={category} onChange={(e) => setCategory(e.target.value as "" | FeedbackCategory)}>
            <option value="">전체</option>
            {(Object.keys(CATEGORY_LABEL) as FeedbackCategory[]).map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c]}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="secondary">
          검색
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
      {!page ? (
        <p>불러오는 중…</p>
      ) : page.items.length === 0 ? (
        <p className="meta">조건에 맞는 요청이 없어요.</p>
      ) : (
        <>
          <p className="meta">{page.totalElements}건</p>
          <ul className="cards">
            {page.items.map((f) => (
              <li key={f.id} className="card">
                <button type="button" className="text-button" aria-expanded={selected?.id === f.id} onClick={() => open(f.id)}>
                  {f.title}
                </button>
                <span className="meta">
                  {CATEGORY_LABEL[f.category]} · {f.author} · {formatSeoul(f.updatedAt)}
                </span>
                <span className={`badge fb-${f.status.toLowerCase()}`}>{STATUS_LABEL[f.status]}</span>
                {selected?.id === f.id && (
                  <ManagePanel
                    item={selected}
                    onSaved={(d) => {
                      setSelected(d);
                      load();
                    }}
                  />
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function ManagePanel({ item, onSaved }: { item: FeedbackDetail; onSaved: (d: FeedbackDetail) => void }) {
  const id = useId();
  const [to, setTo] = useState<FeedbackStatus>(item.status);
  const [reason, setReason] = useState("");
  const [resolution, setResolution] = useState(item.resolution ?? "");
  const [prUrl, setPrUrl] = useState(item.prUrl ?? "");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (NEEDS_REASON.includes(to) && !reason.trim()) {
      setNotice({ kind: "alert", text: "검증 요청·보류로 바꿀 때는 사유를 써 주세요." });
      return;
    }
    setBusy(true);
    try {
      const { data } = await api<FeedbackDetail>(`/api/dev/feedback/${item.id}`, {
        method: "PATCH",
        body: { status: to, reason: reason.trim() || null, resolution, prUrl, version: item.version },
      });
      setReason("");
      setNotice({ kind: "status", text: `상태를 '${STATUS_LABEL[data.status]}'(으)로 저장했어요.` });
      onSaved(data);
    } catch (e) {
      const fields = e instanceof ApiError ? e.fields : undefined;
      setNotice({ kind: "alert", text: fields ? Object.values(fields).join(" ") : errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <div className="manage">
      <FeedbackView item={item} heading="h2" />
      <form className="form" onSubmit={save}>
        <div className="field">
          <label htmlFor={`${id}-to`}>새 상태</label>
          <select id={`${id}-to`} value={to} onChange={(e) => setTo(e.target.value as FeedbackStatus)}>
            {(Object.keys(STATUS_LABEL) as FeedbackStatus[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-reason`}>사유</label>
          <input id={`${id}-reason`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
          <span className="meta">검증 요청·보류는 필수. 작성자에게 보여요.</span>
        </div>
        <div className="field">
          <label htmlFor={`${id}-res`}>처리 내용</label>
          <textarea id={`${id}-res`} value={resolution} maxLength={2000} rows={3} onChange={(e) => setResolution(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-pr`}>PR 링크</label>
          <input id={`${id}-pr`} value={prUrl} maxLength={300} placeholder="https://github.com/…" onChange={(e) => setPrUrl(e.target.value)} />
        </div>
        <div className="actions">
          <button type="submit" disabled={busy}>
            {busy ? "저장 중…" : "상태 저장"}
          </button>
        </div>
        {notice && <p role={notice.kind}>{notice.text}</p>}
      </form>
    </div>
  );
}
