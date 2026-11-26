"use client";

import { useEffect, useState } from "react";

import { api, errorMessage } from "@/lib/api";
import type { Favorites } from "@/lib/profile";
import { useUi } from "@/lib/uiStore";

/** 회차·모임 즐겨찾기 토글. 상태는 aria-pressed 와 글자(☆/★)로 함께 알린다. 서버 PUT·DELETE 는 멱등. */
export function FavoriteButton({ kind, id }: { kind: "sessions" | "clubs"; id: number }) {
  const toast = useUi((s) => s.toast);
  const [on, setOn] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    api<Favorites>("/api/me/favorites")
      .then((res) => active && setOn(res.data[kind].some((f) => f.id === id)))
      .catch(() => active && setOn(false));
    return () => {
      active = false;
    };
  }, [kind, id]);

  async function toggle() {
    setBusy(true);
    try {
      const { data } = await api<{ favorite: boolean }>(`/api/${kind}/${id}/favorite`, { method: on ? "DELETE" : "PUT" });
      setOn(data.favorite);
      toast(data.favorite ? "즐겨찾기에 담았어요." : "즐겨찾기에서 뺐어요.");
    } catch (e) {
      toast(errorMessage(e), "error");
    }
    setBusy(false);
  }

  if (on === null) return null;
  return (
    <button type="button" className="secondary favorite" aria-pressed={on} disabled={busy} onClick={toggle}>
      <span aria-hidden="true">{on ? "★ " : "☆ "}</span>즐겨찾기
    </button>
  );
}
