// 운영자 화면 응답 계약: README "운영자 계정 관리 · 사용량 대시보드 (3단계)".
import type { Role } from "@/lib/api";

export type AccountStatus = "ACTIVE" | "SUSPENDED" | "WITHDRAWN";
export type ModerationAction = "SUSPEND" | "UNSUSPEND" | "FORCE_WITHDRAW";
export type Metric =
  | "SIGNUP"
  | "DEMO_STARTED"
  | "SESSION_CREATED"
  | "RESERVATION_CONFIRMED"
  | "RESERVATION_PROMOTED"
  | "WAITLIST_JOINED"
  | "RESERVATION_CANCELLED"
  | "SESSION_DELETED";

export interface AccountSummary {
  id: number;
  /** 탈퇴한 계정은 없음 */
  loginId: string | null;
  displayName: string;
  role: Role;
  status: AccountStatus;
  createdAt: string;
  suspendedAt: string | null;
  deletedAt: string | null;
  activeReservations: number;
  createdSessions: number;
}

export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Impact {
  id: number;
  label: string;
  role: Role;
  status: AccountStatus;
  upcomingConfirmed: number;
  startedConfirmedKept: number;
  waiting: number;
  promotions: number;
  ownedSessions: number;
  ownedSessionReservations: number;
  canSuspend: boolean;
  canUnsuspend: boolean;
  canWithdraw: boolean;
  blockedReason: string | null;
}

export interface ActionEntry {
  id: number;
  action: ModerationAction;
  reason: string;
  createdAt: string;
  operatorId: number;
  operatorLabel: string;
  targetId: number;
  targetLabel: string;
  cancelledReservations: number;
  deletedSessions: number;
}

export interface CurrentCounts {
  accounts: number;
  operators: number;
  suspended: number;
  sessions: number;
  confirmed: number;
  waiting: number;
}

export interface Usage {
  countingStartedAt: string | null;
  from: string;
  to: string;
  current: { regular: CurrentCounts; demo: CurrentCounts };
  totals: { metric: Metric; regular: number; demo: number }[];
  daily: { day: string; metric: Metric; regular: number; demo: number }[];
}

export const ACCOUNT_STATUS_LABEL: Record<AccountStatus, string> = {
  ACTIVE: "활성",
  SUSPENDED: "정지",
  WITHDRAWN: "탈퇴",
};

export const ACTION_LABEL: Record<ModerationAction, string> = {
  SUSPEND: "이용 정지",
  UNSUSPEND: "정지 해제",
  FORCE_WITHDRAW: "강제탈퇴",
};

export const METRIC_LABEL: Record<Metric, string> = {
  SIGNUP: "가입",
  DEMO_STARTED: "체험 시작",
  SESSION_CREATED: "회차 생성",
  RESERVATION_CONFIRMED: "예약 확정(직접)",
  RESERVATION_PROMOTED: "예약 확정(자동 승격)",
  WAITLIST_JOINED: "대기 신청",
  RESERVATION_CANCELLED: "신청 취소",
  SESSION_DELETED: "회차 삭제",
};

export const CURRENT_LABEL: [keyof CurrentCounts, string][] = [
  ["accounts", "회원 계정"],
  ["operators", "운영자 계정"],
  ["suspended", "이용 정지 계정"],
  ["sessions", "회차"],
  ["confirmed", "확정 예약"],
  ["waiting", "대기 신청"],
];
