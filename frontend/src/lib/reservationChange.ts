// 내 신청 상태가 다른 사람의 취소 등으로 바뀌었을 때의 안내 (강조 효과와 함께 보인다). 다른 모듈에 기대지 않아 node --test 로 검증한다.

export interface ReservationState {
  status: string;
  waitingPosition: number | null;
}

export const HIGHLIGHT_MS = 1600;

/** 대기 → 확정, 또는 대기 순번이 앞당겨졌을 때만 안내한다. 처음 본 상태·본인 신청·취소는 알리지 않는다. */
export function describeChange(before: ReservationState | null, after: ReservationState | null): string | null {
  if (before?.status !== "WAITING" || !after) return null;
  if (after.status === "CONFIRMED") return "대기 순번이 되어 예약이 확정됐어요.";
  const [from, to] = [before.waitingPosition, after.waitingPosition];
  if (after.status === "WAITING" && from !== null && to !== null && to < from) {
    return `대기 순번이 ${from}번에서 ${to}번으로 앞당겨졌어요.`;
  }
  return null;
}
