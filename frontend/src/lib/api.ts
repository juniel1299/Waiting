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
  /** 생성자: 수정·정원 변경 가능 */
  canManage: boolean;
  /** 생성자·운영자: 명단 조회·삭제 가능 */
  canDelete: boolean;
}

export interface Reservation {
  id: number;
  sessionId: number;
  sessionTitle: string;
  startsAt: string;
  location: string;
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
    /** 429·503 의 Retry-After(초). */
    readonly retryAfterSeconds?: number,
    /** 400 VALIDATION_FAILED 의 칸별 안내 (칸 이름 → 문구). */
    readonly fields?: Record<string, string>,
  ) {
    super(message);
  }
}

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// 무료 백엔드는 잠들었다 깨는 데 1~3분이 걸린다(2026-11-20 실측 약 2분 50초). 그동안 프록시·플랫폼은 502/503/504 를 돌려준다.
const WAKING_STATUS = new Set([502, 503, 504]);
const WAKE_MAX_WAIT_MS = 300_000;
const WAKE_RETRY_DELAY_MS = 5_000;
const WAKE_RETRY_DELAY_MAX_MS = 30_000;
/** 이보다 오래 걸리는 요청도 깨는 중으로 보고 안내한다 */
const WAKE_HINT_MS = 3_000;

let wakingRequests = 0;
const wakingListeners = new Set<() => void>();

function changeWaking(delta: number) {
  wakingRequests += delta;
  wakingListeners.forEach((listener) => listener());
}

/** "서버를 깨우는 중" 안내용 구독 (useSyncExternalStore). */
export function subscribeServerWaking(listener: () => void): () => void {
  wakingListeners.add(listener);
  return () => wakingListeners.delete(listener);
}

export function isServerWaking(): boolean {
  return wakingRequests > 0;
}

function retryDelayMs(res: Response | null): number {
  const seconds = Number(res?.headers.get("Retry-After"));
  return seconds > 0 ? Math.min(seconds * 1000, WAKE_RETRY_DELAY_MAX_MS) : WAKE_RETRY_DELAY_MS;
}

/**
 * 백엔드가 깨는 중이면(502/503/504·연결 실패) 상한까지 간격을 두고 다시 보낸다. retry 가 false 면 한 번만 보낸다.
 * 늦거나 다시 보내는 동안엔 깨우는 중 안내를 켠다.
 */
async function sendWhileWaking(send: () => Promise<Response>, retry: boolean): Promise<Response> {
  // e2e 가 상한 초과를 짧게 재현하도록 페이지 전역 값으로만 줄일 수 있다
  const maxWait = (globalThis as { __daumjariWakeMaxWaitMs?: number }).__daumjariWakeMaxWaitMs ?? WAKE_MAX_WAIT_MS;
  const deadline = Date.now() + maxWait;
  let waking = false;
  const markWaking = () => {
    if (!waking) {
      waking = true;
      changeWaking(1);
    }
  };
  const hint = setTimeout(markWaking, WAKE_HINT_MS);
  try {
    for (;;) {
      let res: Response | null = null;
      try {
        res = await send();
      } catch (e) {
        if (!retry || Date.now() >= deadline) throw e;
      }
      if (res && !(retry && WAKING_STATUS.has(res.status) && Date.now() < deadline)) {
        return res;
      }
      markWaking();
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs(res)));
    }
  } finally {
    clearTimeout(hint);
    if (waking) changeWaking(-1);
  }
}

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
  const res = await sendWhileWaking(() => fetch("/api/auth/csrf", { cache: "no-store" }), true);
  if (!res.ok) {
    throw await toApiError(res);
  }
  return ((await res.json()) as { token: string }).token;
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { code?: string; message?: string; fields?: Record<string, string> };
    const retryAfter = Number(res.headers.get("Retry-After"));
    return new ApiError(
      res.status,
      body.code ?? "UNKNOWN",
      body.message ?? "요청을 처리하지 못했어요.",
      retryAfter > 0 ? retryAfter : undefined,
      body.fields,
    );
  } catch {
    return new ApiError(res.status, "UNKNOWN", "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
  }
}

interface ApiInit {
  method?: string;
  body?: unknown;
  /** 깨는 중이면 다시 보낼지. 조회는 기본으로 다시 보내고, 변경은 두 번 처리돼도 무해할 때만 켠다. */
  retryWhileWaking?: boolean;
}

export async function api<T>(path: string, init: ApiInit = {}): Promise<ApiResult<T>> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};
  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (MUTATING.has(method)) {
    headers["X-XSRF-TOKEN"] = await xsrfToken();
  }
  const res = await sendWhileWaking(
    () =>
      fetch(path, {
        method,
        headers,
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        cache: "no-store",
      }),
    init.retryWhileWaking ?? !MUTATING.has(method),
  );
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
