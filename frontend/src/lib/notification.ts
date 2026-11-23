// 알림함 응답 계약은 README "커뮤니티 3단계 · 알림 API" 와 맞춘다.

export type NotificationType = "PROMOTED" | "MENTION";

export interface NotificationItem {
  id: number;
  type: NotificationType;
  read: boolean;
  createdAt: string;
  /** 멘션한 사람의 공개 라벨 (승격 알림은 null) */
  actor: { label: string } | null;
  sessionTitle: string | null;
  postTitle: string | null;
  /** 댓글·답글에서의 멘션이면 참 */
  onComment: boolean;
}

export interface NotificationPage {
  items: NotificationItem[];
  unreadCount: number;
}

/** 머리글 읽지 않은 수 폴링 간격. 빌드 시 NEXT_PUBLIC_NOTIFICATION_POLL_MS 로 바꿀 수 있다 (기본 30초). */
export const NOTIFICATION_POLL_MS = Number(process.env.NEXT_PUBLIC_NOTIFICATION_POLL_MS ?? 30000);

/** 알림함에서 읽음 상태가 바뀌면 머리글 숫자를 바로 다시 읽게 알린다. */
export const NOTIFICATIONS_CHANGED = "notifications-changed";

/** 알림 한 줄 문구. 제목·이름은 서버가 준 현재 값이고 화면에는 글자로만 넣는다. */
export function notificationText(n: NotificationItem): string {
  if (n.type === "PROMOTED") {
    return `대기하던 ‘${n.sessionTitle ?? "회차"}’ 회차가 확정됐어요.`;
  }
  const where = n.onComment ? "댓글" : "글";
  return `${n.actor?.label ?? "누군가"}님이 ‘${n.postTitle ?? "글"}’ ${where}에서 회원님을 언급했어요.`;
}

/** 머리글 버튼 이름: 숫자만으로 뜻이 전해지지 않으므로 읽지 않은 수를 글자로 함께 말한다. */
export function unreadLabel(count: number): string {
  return count > 0 ? `알림, 읽지 않은 알림 ${count}개` : "알림, 읽지 않은 알림 없음";
}
