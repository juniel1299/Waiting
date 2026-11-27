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
/**
 * 한 번에 보내는 크기 상한: Vercel 함수 요청 본문 한도(4.5MB)에 multipart 머리 여유를 두고 4MB.
 * 백엔드는 5MB 까지 받지만(직접 호출용), 화면을 거친 업로드는 이 값을 넘지 않게 브라우저에서 줄인다.
 */
export const MAX_UPLOAD_BYTES = 4 * MB;
/** 브라우저에서도 줄이기 힘든 큰 파일은 처음부터 막는다. */
const HARD_LIMIT = 30 * MB;

const HEIC_GUIDE =
  "HEIC 사진은 아직 받지 않아요. JPEG·PNG·WebP 로 올려 주세요. 아이폰은 설정 > 카메라 > 포맷 > '높은 호환성'으로 찍거나, 사진 앱에서 공유할 때 JPEG 로 바꿔 저장하면 돼요.";

function isHeic(file: { type: string; name: string }): boolean {
  return /^image\/hei[cf]/.test(file.type) || /\.(heic|heif)$/i.test(file.name);
}

export function uploadPlan(file: { type: string; size: number; name: string }): { ok: true; shrink: boolean } | { ok: false; error: string } {
  if (isHeic(file)) return { ok: false, error: HEIC_GUIDE };
  if (!ACCEPTED.includes(file.type)) return { ok: false, error: "JPEG·PNG·WebP 사진만 올릴 수 있어요." };
  if (file.size <= 0) return { ok: false, error: "빈 파일이에요." };
  if (file.size > HARD_LIMIT) return { ok: false, error: "사진이 너무 커요. 30MB 이하로 골라 주세요." };
  return { ok: true, shrink: file.size > MAX_UPLOAD_BYTES };
}

/** 업로드 실패 안내. 프록시가 돌려준 413 은 본문이 JSON 이 아니어서 상태 코드로 판단한다. */
export function uploadFailureText(e: unknown): string {
  const err = e as { status?: number; code?: string; message?: string };
  if (err?.status === 413) return "사진이 너무 커서 보내지 못했어요. 4MB 이하로 줄여 다시 올려 주세요.";
  if (typeof err?.code === "string" && err.code.startsWith("PHOTO_") && err.message) return err.message;
  if (err?.status === 429 && err.message) return err.message;
  return "사진을 올리지 못했어요. 연결을 확인하고 다시 시도해 주세요.";
}
