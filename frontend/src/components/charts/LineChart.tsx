import { chartSummary, linePoints, niceMax, type DayValue } from "@/lib/dashboard";

const W = 640;
const H = 220;
const PAD = { top: 12, right: 12, bottom: 28, left: 40 };

/**
 * 일별 추이 선 차트(SVG). 일반은 실선·원, 데모 체험은 점선·네모로 색 말고도 모양으로 구분한다.
 * 차트 전체가 role="img" 이고 이름에 합계·가장 많은 날을 글자로 담는다. 같은 수치는 아래 표에 있다.
 */
export function LineChart({ label, series }: { label: string; series: DayValue[] }) {
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const max = niceMax(series.flatMap((d) => [d.regular, d.demo]));
  const regular = linePoints(series.map((d) => d.regular), innerW, innerH, max);
  const demo = linePoints(series.map((d) => d.demo), innerW, innerH, max);
  const path = (pts: [number, number][]) => pts.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const ticks = [0, max / 2, max];
  const labelEvery = Math.max(1, Math.ceil(series.length / 6));
  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={chartSummary(label, series)} preserveAspectRatio="xMidYMid meet">
        <g transform={`translate(${PAD.left},${PAD.top})`} aria-hidden="true">
          {ticks.map((t) => (
            <g key={t}>
              <line className="chart-grid" x1={0} x2={innerW} y1={innerH - (t / max) * innerH} y2={innerH - (t / max) * innerH} />
              <text className="chart-axis" x={-6} y={innerH - (t / max) * innerH} textAnchor="end" dominantBaseline="middle">
                {Number.isInteger(t) ? t : t.toFixed(1)}
              </text>
            </g>
          ))}
          {series.map((d, i) =>
            i % labelEvery === 0 ? (
              <text key={d.day} className="chart-axis" x={regular[i][0]} y={innerH + 18} textAnchor="middle">
                {d.day.slice(5)}
              </text>
            ) : null,
          )}
          <path className="chart-line regular" d={path(regular)} />
          <path className="chart-line demo" d={path(demo)} />
          {regular.map(([x, y], i) => (
            <circle key={`r${i}`} className="chart-dot regular" cx={x} cy={y} r={3} />
          ))}
          {demo.map(([x, y], i) => (
            <rect key={`d${i}`} className="chart-dot demo" x={x - 2.5} y={y - 2.5} width={5} height={5} />
          ))}
        </g>
      </svg>
      <figcaption className="chart-legend">
        <span className="legend regular">● 일반 (실선)</span>
        <span className="legend demo">■ 데모 체험 (점선)</span>
      </figcaption>
    </figure>
  );
}
