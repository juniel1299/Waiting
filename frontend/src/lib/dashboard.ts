// 사용량 대시보드 차트 계산(라이브러리 없이 SVG 로 그린다). 다른 모듈에 기대지 않는다(node --test).

export interface DailyRow {
  day: string;
  metric: string;
  regular: number;
  demo: number;
}

export interface DayValue {
  day: string;
  regular: number;
  demo: number;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** from~to(종료일 포함) 모든 날짜 "YYYY-MM-DD". 날짜 문자열을 UTC 자정으로 다뤄 서머타임·시간대 영향이 없다. */
export function daysBetween(from: string, to: string): string[] {
  const days: string[] = [];
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= Date.parse(`${to}T00:00:00Z`); t += DAY_MS) {
    days.push(new Date(t).toISOString().slice(0, 10));
  }
  return days;
}

/** 한 지표의 기간 전체 일별 값(기록 없는 날 0). */
export function dailySeries(usage: { from: string; to: string; daily: DailyRow[] }, metric: string): DayValue[] {
  const byDay = new Map(usage.daily.filter((d) => d.metric === metric).map((d) => [d.day, d]));
  return daysBetween(usage.from, usage.to).map((day) => ({
    day,
    regular: byDay.get(day)?.regular ?? 0,
    demo: byDay.get(day)?.demo ?? 0,
  }));
}

const NICE_STEPS = [1, 1.5, 2, 2.5, 4, 5, 8, 10];

/** 축 최댓값: 가장 큰 값을 1·1.5·2·2.5·4·5·8·10 × 10^k 로 올린다. 모두 0이면 1. */
export function niceMax(values: number[]): number {
  const max = Math.max(0, ...values);
  if (max === 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(max));
  const step = NICE_STEPS.find((s) => s * magnitude >= max) ?? 10;
  return step * magnitude;
}

/** 값들을 안쪽 width×height 안의 [x, y] 로. 값이 하나면 가운데. */
export function linePoints(values: number[], width: number, height: number, max: number): [number, number][] {
  return values.map((v, i) => [
    values.length === 1 ? width / 2 : (i * width) / (values.length - 1),
    height - (v / max) * height,
  ]);
}

/** 스크린 리더용 차트 요약(차트 이름에 들어간다). 같은 수치는 차트 아래 표에도 있다. */
export function chartSummary(label: string, series: DayValue[]): string {
  if (series.length === 0) return `${label} 일별 추이. 기록 없음.`;
  const sum = (k: "regular" | "demo") => series.reduce((acc, d) => acc + d[k], 0);
  const peak = series.reduce((best, d) => (d.regular > best.regular ? d : best), series[0]);
  const peakText = peak.regular > 0 ? `, 가장 많은 날 ${peak.day} ${peak.regular}` : "";
  return `${label} 일별 추이, ${series[0].day}~${series[series.length - 1].day}. 일반 합계 ${sum("regular")}${peakText}. 데모 체험 합계 ${sum("demo")}.`;
}
