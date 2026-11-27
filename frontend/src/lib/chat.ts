// 모임 채팅 응답 형태와 이어 받은 변경 합치기. 다른 모듈에 기대지 않는다(node --test).

export interface ChatMessage {
  id: number;
  author?: { id: number; label: string };
  body: string | null;
  deleted: boolean;
  mine?: boolean;
  canDelete?: boolean;
  createdAt?: string;
}

export interface ChatPage {
  items: ChatMessage[];
  cursor: number;
  hasMore: boolean;
}

/** 채팅 이어 받기 간격. 빌드 시 NEXT_PUBLIC_CHAT_POLL_MS 로 바꿀 수 있다 (기본 3초). */
export const CHAT_POLL_MS = Number(process.env.NEXT_PUBLIC_CHAT_POLL_MS ?? 3000);

/** 기존 목록에 변경(새 메시지·삭제 표시)을 id 기준으로 덮어 합치고 id 순으로 정렬한 새 배열. 변경이 없으면 원래 배열. */
export function mergeMessages<T extends ChatMessage>(current: T[], incoming: T[]): T[] {
  if (incoming.length === 0) return current;
  const byId = new Map(current.map((x) => [x.id, x]));
  for (const x of incoming) byId.set(x.id, x);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}
