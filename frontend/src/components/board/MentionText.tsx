"use client";

import { splitMentions } from "@/lib/mention";

/** 본문을 텍스트 노드로만 그린다(HTML 해석 없음). 연결된 멘션은 현재 닉네임으로 강조해 보인다. */
export function MentionText({ body, mentions }: { body: string; mentions: { id: number; nickname: string }[] }) {
  const names = new Map(mentions.map((m) => [m.id, m.nickname]));
  return (
    <>
      {splitMentions(body, names).map((s, i) =>
        "mention" in s ? (
          <span key={i} className="mention">
            @{s.name}
          </span>
        ) : (
          s.text
        ),
      )}
    </>
  );
}
