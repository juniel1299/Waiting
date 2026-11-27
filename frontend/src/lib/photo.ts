// 모임 사진첩 응답 형태와 올리기 계획. 판단의 최종은 서버(바이트 시그니처·5MB·상한)다. 다른 모듈에 기대지 않는다(node --test).

export interface ClubPhoto {
  id: number;
  width: number;
  height: number;
  uploader: { id: number; label: string };
  mine: boolean;
  canDelete: boolean;
  createdAt: string;
  imageUrl: string;
  thumbUrl: string;
}

export interface PhotoPage {
  items: ClubPhoto[];
  nextBefore: number | null;
  count: number;
  maxPerClub: number;
}

const ACCEPTED = ["image/jpeg", "image/png", "image/webp"];
const MB = 1024 * 1024;
/** Vercel 함수 요청 본문 한도(4.5MB)보다 작게: 넘으면 브라우저에서 줄여 보낸다. */
export const SHRINK_ABOVE = 4 * MB;
/** 브라우저에서도 다루기 힘든 큰 파일은 처음부터 막는다. */
const HARD_LIMIT = 30 * MB;

export function uploadPlan(file: { type: string; size: number }): { ok: true; shrink: boolean } | { ok: false; error: string } {
  if (!ACCEPTED.includes(file.type)) return { ok: false, error: "JPEG·PNG·WebP 사진만 올릴 수 있어요." };
  if (file.size <= 0) return { ok: false, error: "빈 파일이에요." };
  if (file.size > HARD_LIMIT) return { ok: false, error: "사진이 너무 커요. 30MB 이하로 골라 주세요." };
  return { ok: true, shrink: file.size > SHRINK_ABOVE };
}
