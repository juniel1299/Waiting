"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import type { Favorites } from "@/lib/profile";
import { useUi } from "@/lib/uiStore";

export default function FavoritesPage() {
  return <AuthGate>{(me) => (me.demo ? <p>체험 중에는 즐겨찾기를 쓸 수 없어요.</p> : <FavoriteList />)}</AuthGate>;
}

/** 내 활동 > 즐겨찾기: 지금 볼 수 있는 회차·모임만 (서버가 매번 다시 거른다). 여기서 바로 해제한다. */
function FavoriteList() {
  const toast = useUi((s) => s.toast);
  const [data, setData] = useState<Favorites | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    api<Favorites>("/api/me/favorites")
      .then((res) => {
        setData(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [attempt]);

  async function remove(kind: "sessions" | "clubs", id: number) {
    try {
      await api(`/api/${kind}/${id}/favorite`, { method: "DELETE" });
      setData((d) => (d ? { ...d, [kind]: d[kind].filter((f) => f.id !== id) } : d));
      toast("즐겨찾기에서 뺐어요.");
    } catch (e) {
      toast(errorMessage(e), "error");
    }
  }

  if (error) {
    return (
      <>
        <p role="alert">{error}</p>
        <button type="button" onClick={() => setAttempt((a) => a + 1)}>
          다시 시도
        </button>
      </>
    );
  }
  if (!data) return <p>불러오는 중…</p>;
  const unstar = (kind: "sessions" | "clubs", id: number) => (
    <button type="button" className="secondary" onClick={() => remove(kind, id)}>
      즐겨찾기 해제
    </button>
  );
  return (
    <>
      <h1>즐겨찾기</h1>
      <p className="meta">
        회차 {data.sessions.length}개 · 모임 {data.clubs.length}개
      </p>
      <h2>회차</h2>
      {data.sessions.length === 0 ? (
        <p className="meta">
          아직 없어요. <Link href="/sessions">회차 목록</Link>에서 ☆ 즐겨찾기를 눌러 보세요.
        </p>
      ) : (
        <ul className="cards">
          {data.sessions.map((s) => (
            <li key={s.id} className="card fav-row">
              <Link href={`/sessions/${s.id}`}>{s.title}</Link>
              <span className="meta">
                {formatSeoul(s.startsAt)} · {s.location}
              </span>
              {unstar("sessions", s.id)}
            </li>
          ))}
        </ul>
      )}
      <h2>모임</h2>
      {data.clubs.length === 0 ? (
        <p className="meta">
          아직 없어요. <Link href="/clubs">모임 목록</Link>에서 ☆ 즐겨찾기를 눌러 보세요.
        </p>
      ) : (
        <ul className="cards">
          {data.clubs.map((c) => (
            <li key={c.id} className="card fav-row">
              <Link href={`/clubs/${c.id}`}>{c.name}</Link>
              <span className="meta">{c.place}</span>
              {unstar("clubs", c.id)}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
