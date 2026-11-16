// 백엔드 응답 계약은 README "인증 / 회차 API / 예약 API / API 오류 규칙" 과 맞춘다.

export type Role = "OPERATOR" | "PARTICIPANT";
export type SessionStatus = "OPEN" | "FULL" | "STARTED";
export type ReservationStatus = "CONFIRMED" | "WAITING" | "CANCELLED";

export interface Me {
  id: number;
  loginId: string;
  displayName: string;
  role: Role;
  /** 데모 체험 방문자 */
  demo: boolean;
}

export interface StudySession {
  id: number;
  title: string;
  startsAt: string;
  location: string;
  capacity: number;
  confirmedCount: number;
  remainingSeats: number;
  waitingCount: number;
  status: SessionStatus;
  /** 본인의 활성 신청(확정·대기). waitingPosition 은 대기일 때만 1부터. */
  myReservation: { id: number; status: ReservationStatus; waitingPosition: number | null } | null;
  /** 데모 체험 회차일 때만: 체험 데이터 삭제 시각과 지정 샘플 예약이 아직 확정인지 */
  demo: { expiresAt: string; sampleReservationActive: boolean } | null;
}

export interface Reservation {
  id: number;
  sessionId: number;
  sessionTitle: string;
  startsAt: string;
  status: ReservationStatus;
  waitingPosition: number | null;
  createdAt: string;
  cancelledAt: string | null;
}

export interface RosterEntry {
  reservationId: number;
  accountId: number;
  loginId: string;
  displayName: string;
  reservedAt: string;
}

export interface ApiResult<T> {
  status: number;
  data: T;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    /** 429 의 Retry-After(초). */
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  const found = document.cookie.split("; ").find((c) => c.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : null;
}

/** 쿠키 이중 제출 CSRF: 쿠키가 없으면(첫 요청·로그인 직후) 새로 발급받는다. */
async function xsrfToken(): Promise<string> {
  const cached = readCookie("XSRF-TOKEN");
  if (cached) {
    return cached;
  }
  const res = await fetch("/api/auth/csrf", { cache: "no-store" });
  if (!res.ok) {
    throw await toApiError(res);
  }
  return ((await res.json()) as { token: string }).token;
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { code?: string; message?: string };
    const retryAfter = Number(res.headers.get("Retry-After"));
    return new ApiError(
      res.status,
      body.code ?? "UNKNOWN",
      body.message ?? "요청을 처리하지 못했어요.",
      retryAfter > 0 ? retryAfter : undefined,
    );
  } catch {
    return new ApiError(res.status, "UNKNOWN", "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
  }
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<ApiResult<T>> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};
  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (MUTATING.has(method)) {
    headers["X-XSRF-TOKEN"] = await xsrfToken();
  }
  const res = await fetch(path, {
    method,
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    cache: "no-store",
  });
  if (!res.ok) {
    throw await toApiError(res);
  }
  const text = await res.text();
  return { status: res.status, data: (text ? JSON.parse(text) : undefined) as T };
}

const MESSAGE_BY_CODE: Record<string, string> = {
  SESSION_FULL: "정원이 마감됐어요. 다른 회차를 확인해 주세요.",
  SESSION_STARTED: "이미 시작된 회차라 예약·취소할 수 없어요.",
  SEATS_AVAILABLE: "빈자리가 생겼어요. 대기 대신 바로 예약해 주세요.",
};

/** 내 신청 상태 표시: 예약 확정 / 대기 N번 / 예약 없음. */
export function myReservationLabel(mine: StudySession["myReservation"]): string {
  if (!mine) {
    return "예약 없음";
  }
  return mine.status === "WAITING" ? `대기 ${mine.waitingPosition}번` : "예약 확정";
}

/** 초를 "N분 M초" / "M초" 로. */
export function formatWait(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes === 0) {
    return `${rest}초`;
  }
  return rest === 0 ? `${minutes}분` : `${minutes}분 ${rest}초`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === "TOO_MANY_ATTEMPTS") {
    const wait = error.retryAfterSeconds ? `${formatWait(error.retryAfterSeconds)} 후` : "잠시 후";
    return `로그인 시도가 너무 많아요. ${wait} 다시 시도해 주세요.`;
  }
  if (error instanceof ApiError) {
    return MESSAGE_BY_CODE[error.code] ?? error.message;
  }
  return "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
}

const seoulFormat = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  dateStyle: "medium",
  timeStyle: "short",
});

export function formatSeoul(iso: string): string {
  return seoulFormat.format(new Date(iso));
}

/** datetime-local 값(서울 기준 "2030-03-02T19:30")을 UTC ISO 문자열로. */
export function seoulLocalToIso(local: string): string {
  return new Date(`${local}:00+09:00`).toISOString();
}

export const STATUS_LABEL: Record<SessionStatus, string> = {
  OPEN: "예약 가능",
  FULL: "마감",
  STARTED: "시작됨",
};
