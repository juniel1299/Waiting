export interface BarRow {
  key: string;
  label: string;
  regular: number;
  demo: number;
}

const W = 640;
const ROW = 34;
const LABEL_W = 150;
const VALUE_W = 40;

/** 지표별 합계 가로 막대(SVG). 일반은 채운 막대, 데모 체험은 빗금 막대. 같은 수치는 아래 표에 있다. */
export function BarChart({ title, rows }: { title: string; rows: BarRow[] }) {
  const max = Math.max(1, ...rows.flatMap((r) => [r.regular, r.demo]));
  const barW = W - LABEL_W - VALUE_W;
  const H = rows.length * ROW;
  const name = `${title}. ` + rows.map((r) => `${r.label} 일반 ${r.regular}, 데모 체험 ${r.demo}`).join("; ") + ".";
  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={name} preserveAspectRatio="xMidYMid meet">
        <defs>
          <pattern id="demo-hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect className="chart-hatch-bg" width="6" height="6" />
            <line className="chart-hatch" x1="0" y1="0" x2="0" y2="6" />
          </pattern>
        </defs>
        <g aria-hidden="true">
          {rows.map((r, i) => {
            const y = i * ROW;
            const rw = (r.regular / max) * barW;
            const dw = (r.demo / max) * barW;
            return (
              <g key={r.key}>
                <text className="chart-axis" x={LABEL_W - 8} y={y + ROW / 2} textAnchor="end" dominantBaseline="middle">
                  {r.label}
                </text>
                <rect className="chart-bar regular" x={LABEL_W} y={y + 5} width={Math.max(rw, r.regular ? 2 : 0)} height={11} />
                <text className="chart-axis" x={LABEL_W + rw + 4} y={y + 11} dominantBaseline="middle">
                  {r.regular}
                </text>
                <rect className="chart-bar demo" x={LABEL_W} y={y + 18} width={Math.max(dw, r.demo ? 2 : 0)} height={11} fill="url(#demo-hatch)" />
                <text className="chart-axis" x={LABEL_W + dw + 4} y={y + 24} dominantBaseline="middle">
                  {r.demo}
                </text>
              </g>
            );
          })}
        </g>
      </svg>
      <figcaption className="chart-legend">
        <span className="legend regular">■ 일반 (채움)</span>
        <span className="legend demo">▨ 데모 체험 (빗금)</span>
      </figcaption>
    </figure>
  );
}
