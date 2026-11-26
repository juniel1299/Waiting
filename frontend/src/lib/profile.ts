// 프로필·즐겨찾기 응답 형태와 태그 입력 변환. 규칙 판단은 서버가 다시 한다.

export interface Profile {
  id: number;
  nickname: string;
  bio: string | null;
  tags: string[];
}

export interface Favorites {
  sessions: { id: number; title: string; startsAt: string; location: string }[];
  clubs: { id: number; name: string; place: string }[];
}

/** "자바, 스프링 ,자바" → ["자바","스프링"] (쉼표로 나누고 앞뒤 공백·빈 값·중복 제거). */
export function parseTags(input: string): string[] {
  return [...new Set(input.split(",").map((t) => t.trim()).filter(Boolean))];
}
