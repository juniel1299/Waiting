// 멘션 토큰 `@[이름#계정id]` 처리 (서버 Mentions 와 같은 형식). 이름은 쓸 때의 표시이고 화면은 현재 닉네임을 쓴다.
// 다른 모듈에 기대지 않아 node --test 로 검증한다.

const TOKEN = /@\[[^\]\n#]{0,40}#(\d{1,18})\]/g;

export type Segment = { text: string } | { mention: number; name: string };

/** 본문을 글자·멘션 조각으로. 연결되지 않은(없거나 탈퇴 전 응답에 없는) 토큰은 글자 그대로 둔다. */
export function splitMentions(body: string, names: Map<number, string>): Segment[] {
  const out: Segment[] = [];
  let last = 0;
  let text = "";
  for (const m of body.matchAll(TOKEN)) {
    const id = Number(m[1]);
    const name = names.get(id);
    if (name === undefined) continue;
    text += body.slice(last, m.index);
    if (text) out.push({ text });
    text = "";
    out.push({ mention: id, name });
    last = (m.index ?? 0) + m[0].length;
  }
  text += body.slice(last);
  if (text) out.push({ text });
  return out;
}

/** 커서 바로 앞이 `@검색어`(공백·기호 없음, 1~20자)이고 @ 앞이 줄 처음이나 공백이면 그 검색어와 @ 위치. */
export function mentionQueryAt(text: string, cursor: number): { query: string; start: number } | null {
  const before = text.slice(0, cursor);
  const m = /(^|\s)@([^\s@[\]#]{1,20})$/.exec(before);
  if (!m) return null;
  return { query: m[2], start: before.length - m[2].length - 1 };
}

/** start(@ 위치)부터 cursor 까지를 토큰과 공백으로 바꾼다. */
export function insertMention(
  text: string,
  start: number,
  cursor: number,
  person: { id: number; nickname: string },
): { text: string; cursor: number } {
  const token = `@[${person.nickname}#${person.id}] `;
  return { text: text.slice(0, start) + token + text.slice(cursor), cursor: start + token.length };
}
