// 카카오·네이버 계정 연결: 공급자 이름과 콜백 결과 안내. 다른 모듈에 기대지 않는다(node --test).

export type ProviderPath = "kakao" | "naver";

export const PROVIDERS: ProviderPath[] = ["kakao", "naver"];

export const PROVIDER_LABEL: Record<ProviderPath, string> = { kakao: "카카오", naver: "네이버" };

const ERROR_TEXT: Record<string, string> = {
  OAUTH_STATE_INVALID: "연결 요청이 만료됐거나 올바르지 않아요. 처음부터 다시 시도해 주세요.",
  OAUTH_DENIED: "동의를 취소해서 연결하지 않았어요.",
  OAUTH_FAILED: "공급자에서 계정을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.",
  OAUTH_PROVIDER_DISABLED: "이 로그인 방식은 아직 준비 중이에요.",
  SOCIAL_ALREADY_LINKED: "이 소셜 계정은 이미 다른 회원에게 연결돼 있어요.",
  SOCIAL_PROVIDER_ALREADY_LINKED: "이미 같은 공급자의 다른 계정이 연결돼 있어요. 먼저 해제해 주세요.",
  SOCIAL_FORBIDDEN: "체험 계정은 소셜 계정을 연결할 수 없어요.",
  SOCIAL_NOT_LINKED: "이 소셜 계정에 연결된 계정이 없어요. 아이디로 로그인한 뒤 내 정보 > 로그인 계정 연결에서 먼저 연결해 주세요.",
  LOGIN_REQUIRED: "로그인한 뒤 다시 시도해 주세요.",
  ACCOUNT_SUSPENDED: "이용이 정지된 계정이에요. 운영자에게 문의해 주세요.",
};

/** 주소의 ?linked=공급자 / ?error=코드 를 화면 안내로. 모르는 값은 일반 안내, 아무것도 없으면 null. */
export function socialNotice(params: URLSearchParams): { kind: "status" | "alert"; text: string } | null {
  const linked = params.get("linked");
  if (linked === "kakao" || linked === "naver") {
    return { kind: "status", text: `${PROVIDER_LABEL[linked]} 계정을 연결했어요.` };
  }
  const error = params.get("error");
  if (!error) return null;
  return { kind: "alert", text: ERROR_TEXT[error] ?? "소셜 계정 처리 중 문제가 생겼어요. 다시 시도해 주세요." };
}
