"use client";

import { useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { UsageCharts } from "@/components/dashboard/UsageCharts";
import { CurrentCards, CurrentTable, DailyTable, TotalsTable } from "@/components/dashboard/UsageTables";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import type { Usage } from "@/lib/operator";

type View = "table" | "chart";
const VIEW_KEY = "daumjari:dashboard-view";

/**
 * 운영자 사용량: 현재 데이터(지금 남은 행, 삭제되면 줄어듦)는 카드+표, 누적 사용량(성공 횟수, 원본을 지워도 유지)은 표 ↔ 차트.
 * 둘 다 일반·데모 체험을 나눈다. 고른 보기는 이 브라우저에 기억한다.
 */
export default function OperatorPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "OPERATOR" ? <UsageDashboard /> : <p role="alert">권한이 없습니다.</p>)}
    </AuthGate>
  );
}

function savedView(): View {
  try {
    return localStorage.getItem(VIEW_KEY) === "chart" ? "chart" : "table";
  } catch {
    return "table";
  }
}

function UsageDashboard() {
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [view, setView] = useState<View>(savedView);

  useEffect(() => {
    const ctrl = new AbortController();
    const query = range ? `?from=${range.from}&to=${range.to}` : "";
    api<Usage>(`/api/operator/usage${query}`, { signal: ctrl.signal })
      .then((res) => {
        setUsage(res.data);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted) setError(errorMessage(e));
      });
    return () => ctrl.abort();
  }, [range, attempt]);

  function choose(next: View) {
    setView(next);
    try {
      localStorage.setItem(VIEW_KEY, next);
    } catch {
      // 저장소를 못 써도 이번 화면에서는 바뀐다
    }
  }

  return (
    <>
      <OperatorHead title="사용량" />
      {error && (
        <>
          <p role="alert">사용량을 불러오지 못했어요. {error}</p>
          <button
            type="button"
            onClick={() => {
              setError(null);
              setAttempt((a) => a + 1);
            }}
          >
            다시 시도
          </button>
        </>
      )}
      {!usage && !error && <p role="status">불러오는 중…</p>}
      {usage && !error && (
        <>
          <section className="panel" aria-labelledby="current-heading">
            <h2 id="current-heading">현재 데이터</h2>
            <p className="meta">지금 남아 있는 수예요. 회차·신청이 삭제되거나 회원이 탈퇴하면 줄어요. 큰 수는 일반, 작은 수는 데모 체험이에요.</p>
            <CurrentCards usage={usage} />
            <CurrentTable usage={usage} />
          </section>
          <section className="panel" aria-labelledby="cumulative-heading">
            <h2 id="cumulative-heading">누적 사용량</h2>
            <p className="meta">
              성공한 동작의 횟수예요. 원본을 지워도 줄지 않아요. 집계 시작:{" "}
              <strong>{usage.countingStartedAt ? formatSeoul(usage.countingStartedAt) : "-"}</strong> (그 전 기록은 만들지
              않았어요).
            </p>
            <RangeForm key={`${usage.from}-${usage.to}`} from={usage.from} to={usage.to} onApply={setRange} />
            <div className="view-toggle" role="group" aria-label="보기 방식">
              <button type="button" className="secondary" aria-pressed={view === "table"} onClick={() => choose("table")}>
                표
              </button>
              <button type="button" className="secondary" aria-pressed={view === "chart"} onClick={() => choose("chart")}>
                차트
              </button>
            </div>
            {view === "chart" ? (
              <UsageCharts usage={usage} />
            ) : (
              <>
                <TotalsTable usage={usage} testIds />
                <DailyTable usage={usage} />
              </>
            )}
          </section>
        </>
      )}
    </>
  );
}

function RangeForm({ from, to, onApply }: { from: string; to: string; onApply: (r: { from: string; to: string }) => void }) {
  const [start, setStart] = useState(from);
  const [end, setEnd] = useState(to);
  function submit(event: FormEvent) {
    event.preventDefault();
    onApply({ from: start, to: end });
  }
  return (
    <form className="search-row" onSubmit={submit} aria-label="누적 사용량 기간">
      <div className="field">
        <label htmlFor="usage-from">집계 시작일</label>
        <input id="usage-from" type="date" value={start} max={end} onChange={(e) => setStart(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="usage-to">집계 종료일</label>
        <input id="usage-to" type="date" value={end} min={start} onChange={(e) => setEnd(e.target.value)} required />
      </div>
      <div className="field end">
        <button type="submit">기간 적용</button>
      </div>
    </form>
  );
}
