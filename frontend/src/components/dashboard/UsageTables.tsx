import { CURRENT_LABEL, METRIC_LABEL, type Metric, type Usage } from "@/lib/operator";

/** 현재 데이터 카드: 항목마다 일반(큰 수)과 데모 체험(작은 수). 같은 값이 아래 표에도 있다. */
export function CurrentCards({ usage }: { usage: Usage }) {
  return (
    <ul className="stat-cards">
      {CURRENT_LABEL.map(([key, label]) => (
        <li key={key} className="stat-card" data-testid={`card-${key}`}>
          <span className="meta">{label}</span>
          <strong className="stat-value" data-testid="card-regular">
            {usage.current.regular[key]}
          </strong>
          <span className="meta">데모 체험 {usage.current.demo[key]}</span>
        </li>
      ))}
    </ul>
  );
}

export function CurrentTable({ usage }: { usage: Usage }) {
  return (
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
  );
}

/** 지표별 합계 표. testIds 가 참이면 표 보기용 testid 를 붙인다(차트 보기의 같은 표와 겹치지 않게). */
export function TotalsTable({ usage, testIds }: { usage: Usage; testIds: boolean }) {
  return (
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
              <td data-testid={testIds ? `total-regular-${t.metric}` : undefined}>{t.regular}</td>
              <td>{t.demo}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 날짜별 일반 횟수 (데모가 있으면 괄호로). 기록이 있는 날만. */
export function DailyTable({ usage }: { usage: Usage }) {
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
