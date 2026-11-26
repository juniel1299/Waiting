"use client";

import { useId, useState } from "react";

import { BarChart } from "@/components/charts/BarChart";
import { LineChart } from "@/components/charts/LineChart";
import { TotalsTable } from "@/components/dashboard/UsageTables";
import { dailySeries } from "@/lib/dashboard";
import { METRIC_LABEL, type Metric, type Usage } from "@/lib/operator";

/** 누적 사용량 차트 보기: 고른 지표의 일별 추이(선)와 지표별 합계(막대). 두 차트의 수치는 바로 아래 표에도 있다. */
export function UsageCharts({ usage }: { usage: Usage }) {
  const id = useId();
  const [metric, setMetric] = useState<Metric>("SIGNUP");
  if (usage.daily.length === 0) {
    return <p>이 기간에는 기록이 없어요.</p>;
  }
  const series = dailySeries(usage, metric);
  const sum = (k: "regular" | "demo") => series.reduce((acc, d) => acc + d[k], 0);
  return (
    <>
      <h3>일별 추이</h3>
      <div className="field narrow-field">
        <label htmlFor={`${id}-metric`}>지표</label>
        <select id={`${id}-metric`} value={metric} onChange={(e) => setMetric(e.target.value as Metric)}>
          {usage.totals.map((t) => (
            <option key={t.metric} value={t.metric}>
              {METRIC_LABEL[t.metric]}
            </option>
          ))}
        </select>
      </div>
      <LineChart label={METRIC_LABEL[metric]} series={series} />
      <dl className="chart-sums">
        <dt>일반 합계</dt>
        <dd data-testid="chart-sum-regular">{sum("regular")}</dd>
        <dt>데모 체험 합계</dt>
        <dd data-testid="chart-sum-demo">{sum("demo")}</dd>
      </dl>
      <details>
        <summary>날짜별 수치 표</summary>
        <div className="table-scroll">
          <table>
            <caption className="meta">{METRIC_LABEL[metric]} 날짜별 (기록 없는 날 0)</caption>
            <thead>
              <tr>
                <th scope="col">날짜</th>
                <th scope="col">일반</th>
                <th scope="col">데모 체험</th>
              </tr>
            </thead>
            <tbody>
              {series.map((d) => (
                <tr key={d.day}>
                  <th scope="row">{d.day}</th>
                  <td>{d.regular}</td>
                  <td>{d.demo}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
      <h3>지표별 합계</h3>
      <BarChart
        title={`지표별 합계, ${usage.from}~${usage.to}`}
        rows={usage.totals.map((t) => ({ key: t.metric, label: METRIC_LABEL[t.metric], regular: t.regular, demo: t.demo }))}
      />
      <TotalsTable usage={usage} testIds={false} />
    </>
  );
}
