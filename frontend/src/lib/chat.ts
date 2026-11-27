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
  /** 처음 조회에서 더 오래된 메시지가 있는지 */
  hasMoreBefore: boolean;
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

/** 이전 기록 한 쪽(이어 받기 cursor 는 없다: 읽음 위치를 되돌리지 않는다). */
export interface ChatHistory {
  items: ChatMessage[];
  hasMore: boolean;
}

interface ScrollBox {
  scrollHeight: number;
  scrollTop: number;
  clientHeight?: number;
}

/** 위에 기록을 붙인 뒤의 scrollTop: 늘어난 높이만큼 내려 보던 메시지가 같은 자리에 남게 한다. */
export function anchorScrollTop(before: ScrollBox, newScrollHeight: number): number {
  return before.scrollTop + (newScrollHeight - before.scrollHeight);
}

/** 맨 아래 근처면 새 메시지를 따라 내려간다. */
export function isNearBottom(box: Required<ScrollBox>, threshold = 120): boolean {
  return box.scrollHeight - box.scrollTop - box.clientHeight <= threshold;
}

/** 이전 목록 끝보다 뒤(더 큰 id)에 새로 붙은 남의 메시지 수. 이전 기록·내 메시지는 세지 않는다. */
export function appendedCount(before: ChatMessage[], after: ChatMessage[]): number {
  if (before.length === 0) return 0;
  const last = before[before.length - 1].id;
  return after.filter((x) => x.id > last && !x.mine).length;
}
