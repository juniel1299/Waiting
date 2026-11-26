// 개선사항·에러 로그 응답 형태와 표시 문구. 판단은 서버가 한다.

export type FeedbackStatus = "RECEIVED" | "IN_PROGRESS" | "VERIFY_REQUESTED" | "DONE" | "ON_HOLD";
export type FeedbackCategory = "BUG" | "IDEA" | "OTHER";

export const STATUS_LABEL: Record<FeedbackStatus, string> = {
  RECEIVED: "접수",
  IN_PROGRESS: "진행 중",
  VERIFY_REQUESTED: "검증 요청",
  DONE: "완료",
  ON_HOLD: "보류",
};

export const CATEGORY_LABEL: Record<FeedbackCategory, string> = { BUG: "오류 신고", IDEA: "기능 제안", OTHER: "기타" };

/** 사유가 꼭 필요한 상태 */
export const NEEDS_REASON: FeedbackStatus[] = ["VERIFY_REQUESTED", "ON_HOLD"];

export interface FeedbackSummary {
  id: number;
  category: FeedbackCategory;
  title: string;
  status: FeedbackStatus;
  author: string;
  createdAt: string;
  updatedAt: string;
}

export interface FeedbackDetail extends FeedbackSummary {
  body: string;
  pagePath: string | null;
  resolution: string | null;
  prUrl: string | null;
  version: number;
  history: { fromStatus: FeedbackStatus; toStatus: FeedbackStatus; reason: string | null; createdAt: string }[];
}

export interface ErrorGroup {
  id: number;
  source: "FRONTEND" | "BACKEND";
  message: string;
  path: string;
  release: string;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  lastTraceId: string;
  sentryUrl: string | null;
}
