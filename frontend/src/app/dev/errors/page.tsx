"use client";

import { useEffect, useId, useState, type FormEvent } from "react";

import { DevGate } from "@/components/DevGate";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import type { ErrorGroup } from "@/lib/feedback";

const SOURCE_LABEL = { FRONTEND: "화면", BACKEND: "서버" } as const;

export default function DevErrorsPage() {
  return <DevGate>{() => <ErrorLog />}</DevGate>;
}

/** 개발 > 에러 로그: 같은 오류를 묶은 요약(가린 메시지·경로 틀·배포 버전·횟수·처음/마지막·추적 ID). 30일 지나면 지워진다. */
function ErrorLog() {
  const id = useId();
  const [q, setQ] = useState("");
  const [source, setSource] = useState("");
  const [query, setQuery] = useState({ q: "", source: "" });
  const [attempt, setAttempt] = useState(0);
  const [data, setData] = useState<{ items: ErrorGroup[]; totalElements: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(Object.entries(query).filter(([, v]) => v));
    api<{ items: ErrorGroup[]; totalElements: number }>(`/api/dev/errors?${params}`)
      .then((res) => {
        setData(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [query, attempt]);

  function search(event: FormEvent) {
    event.preventDefault();
    setQuery({ q: q.trim(), source });
  }

  return (
    <>
      <h1>에러 로그</h1>
      <p className="meta">비밀번호·토큰·쿠키·이메일은 저장 전에 가려요. 같은 오류는 한 줄로 묶고, 마지막으로 난 지 30일이 지나면 지워요.</p>
      <form className="filters" onSubmit={search}>
        <div className="field">
          <label htmlFor={`${id}-q`}>검색어</label>
          <input id={`${id}-q`} value={q} placeholder="메시지·경로·추적 ID" onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-src`}>출처</label>
          <select id={`${id}-src`} value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="">전체</option>
            <option value="FRONTEND">화면</option>
            <option value="BACKEND">서버</option>
          </select>
        </div>
        <button type="submit" className="secondary">
          검색
        </button>
      </form>
      {error ? (
        <>
          <p role="alert">{error}</p>
          <button type="button" onClick={() => setAttempt((a) => a + 1)}>
            다시 시도
          </button>
        </>
      ) : !data ? (
        <p>불러오는 중…</p>
      ) : data.items.length === 0 ? (
        <p className="meta">모인 오류가 없어요.</p>
      ) : (
        <>
          <p className="meta">{data.totalElements}종류</p>
          <ul className="cards">
            {data.items.map((g) => (
              <li key={g.id} className="card" data-testid="error-row">
                <span className="error-message">{g.message}</span>
                <span className="meta">
                  <span className={`badge src-${g.source.toLowerCase()}`}>{SOURCE_LABEL[g.source]}</span> {g.path} · 배포 {g.release} · {g.occurrences}회
                </span>
                <span className="meta">
                  처음 {formatSeoul(g.firstSeenAt)} · 마지막 {formatSeoul(g.lastSeenAt)} · 추적 ID <code>{g.lastTraceId}</code>
                </span>
                {g.sentryUrl && (
                  <a href={g.sentryUrl} target="_blank" rel="noopener noreferrer" className="link">
                    Sentry 에서 자세히
                  </a>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
