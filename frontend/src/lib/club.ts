// 모임 응답 계약은 README "커뮤니티 4단계 · 모임 API" 와 맞춘다. 다른 모듈에 기대지 않는다(node --test).

export type ClubMode = "OFFLINE" | "ONLINE" | "MIXED";
export type ClubStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";

export interface Club {
  id: number;
  name: string;
  intro: string;
  place: string;
  mode: ClubMode;
  status: ClubStatus;
  /** 생성자·서비스 운영자에게만 */
  rejectReason: string | null;
  version: number | null;
  creator: { label: string };
  memberCount: number;
  joined: boolean;
  /** 내 모임 안 역할 (회원이 아니면 null) */
  myRole: "MANAGER" | "MEMBER" | null;
  mine: boolean;
  submittedAt: string;
  decidedAt: string | null;
  canEdit: boolean;
  canDelete: boolean;
  canCancel: boolean;
  canJoin: boolean;
  canLeave: boolean;
}

export interface ClubPage {
  items: Club[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  canApply: boolean;
}

export interface ClubMember {
  accountId: number;
  label: string;
  role: "MANAGER" | "MEMBER";
  creator: boolean;
  joinedAt: string;
}

/** 회원 목록의 역할 글자 (색만으로 구분하지 않는다). */
export function memberRoleLabel(m: Pick<ClubMember, "role" | "creator">): string {
  if (m.creator) return "만든 사람 · 모임 운영자";
  return m.role === "MANAGER" ? "모임 운영자" : "회원";
}

export const CLUB_MODES: readonly (readonly [ClubMode, string])[] = [
  ["OFFLINE", "오프라인"],
  ["ONLINE", "온라인"],
  ["MIXED", "온·오프라인"],
];

export function modeLabel(mode: ClubMode): string {
  return CLUB_MODES.find(([value]) => value === mode)?.[1] ?? mode;
}

/** 진행 단계는 색만으로 구분하지 않도록 늘 글자로도 보인다. */
export const STATUS_LABELS: Record<ClubStatus, string> = {
  PENDING: "승인 대기",
  APPROVED: "승인됨",
  REJECTED: "반려됨",
  CANCELLED: "신청 취소",
};

/** 내 모임의 진행 단계 안내 한 줄. */
export function stageHint(status: ClubStatus): string {
  switch (status) {
    case "PENDING":
      return "운영자가 신청을 확인하고 있어요. 기다리는 동안 고치거나 취소할 수 있어요.";
    case "APPROVED":
      return "승인됐어요. 모임 목록에 보이고 누구나 가입할 수 있어요.";
    case "REJECTED":
      return "반려됐어요. 사유를 보고 신청서를 고치면 다시 승인을 기다려요.";
    case "CANCELLED":
      return "신청을 취소했어요. 신청서를 고치면 다시 신청돼요.";
  }
}

/** 수정 화면 안내: 승인 모임은 바로 반영, 반려·취소는 재신청, 대기는 그대로 대기. */
export function editHint(status: ClubStatus): string {
  if (status === "APPROVED") return "승인된 모임은 고치면 재승인 없이 바로 반영돼요.";
  if (status === "PENDING") return "승인 대기 중이에요. 고친 내용으로 운영자가 확인해요.";
  return "고쳐서 저장하면 다시 승인을 기다려요.";
}
