"use client";

import { useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import { CURRENT_LABEL, METRIC_LABEL, type Metric, type Usage } from "@/lib/operator";

/** 운영자 사용량: 현재 데이터(지금 남은 행, 삭제되면 줄어듦)와 누적 사용량(성공 횟수, 원본을 지워도 유지)을 나눠 보인다. */
export default function OperatorPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "OPERATOR" ? <UsageDashboard /> : <p role="alert">권한이 없습니다.</p>)}
    </AuthGate>
  );
}

function UsageDashboard() {
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const query = range ? `?from=${range.from}&to=${range.to}` : "";
    api<Usage>(`/api/operator/usage${query}`)
      .then((res) => {
        setUsage(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [range]);

  return (
    <>
      <OperatorHead title="사용량" />
      {error && <p role="alert">{error}</p>}
      {!usage && !error && <p>불러오는 중…</p>}
      {usage && (
        <>
          <section className="panel" aria-labelledby="current-heading">
            <h2 id="current-heading">현재 데이터</h2>
            <p className="meta">지금 남아 있는 수예요. 회차·신청이 삭제되거나 회원이 탈퇴하면 줄어요.</p>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th scope="col">항목</th>
                    <th scope="col">일반</th>
                    <th scope="col">데모 체험</th>
                  </tr>
                </thead>
                <tbody>
                  {CURRENT_LABEL.map(([key, label]) => (
                    <tr key={key}>
                      <th scope="row">{label}</th>
                      <td data-testid={`current-regular-${key}`}>{usage.current.regular[key]}</td>
                      <td>{usage.current.demo[key]}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <section className="panel" aria-labelledby="cumulative-heading">
            <h2 id="cumulative-heading">누적 사용량</h2>
            <p className="meta">
              성공한 동작의 횟수예요. 원본을 지워도 줄지 않아요. 집계 시작:{" "}
              <strong>{usage.countingStartedAt ? formatSeoul(usage.countingStartedAt) : "-"}</strong> (그 전 기록은 만들지
              않았어요).
            </p>
            <RangeForm key={`${usage.from}-${usage.to}`} from={usage.from} to={usage.to} onApply={setRange} />
            <div className="table-scroll">
              <table>
                <caption className="meta">
                  {usage.from} ~ {usage.to} (서울 날짜, 종료일 포함)
                </caption>
                <thead>
                  <tr>
                    <th scope="col">지표</th>
                    <th scope="col">일반</th>
                    <th scope="col">데모 체험</th>
                  </tr>
                </thead>
                <tbody>
                  {usage.totals.map((t) => (
                    <tr key={t.metric}>
                      <th scope="row">{METRIC_LABEL[t.metric]}</th>
                      <td data-testid={`total-regular-${t.metric}`}>{t.regular}</td>
                      <td>{t.demo}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <DailyTable usage={usage} />
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

/** 날짜별 일반 횟수 (데모가 있으면 괄호로). 기록이 있는 날만. */
function DailyTable({ usage }: { usage: Usage }) {
  const metrics = usage.totals.map((t) => t.metric);
  const days = [...new Set(usage.daily.map((d) => d.day))];
  const cell = (day: string, metric: Metric) => {
    const row = usage.daily.find((d) => d.day === day && d.metric === metric);
    if (!row) return "0";
    return row.demo > 0 ? `${row.regular} (${row.demo})` : String(row.regular);
  };
  if (days.length === 0) {
    return <p>이 기간에는 기록이 없어요.</p>;
  }
  return (
    <div className="table-scroll">
      <table>
        <caption className="meta">날짜별 (괄호 안은 데모 체험)</caption>
        <thead>
          <tr>
            <th scope="col">날짜</th>
            {metrics.map((m) => (
              <th key={m} scope="col">
                {METRIC_LABEL[m]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {days.map((day) => (
            <tr key={day}>
              <th scope="row">{day}</th>
              {metrics.map((m) => (
                <td key={m}>{cell(day, m)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
