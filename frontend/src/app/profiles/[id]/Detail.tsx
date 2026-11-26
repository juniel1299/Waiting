"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { TagList } from "@/components/TagList";
import { api, ApiError, errorMessage } from "@/lib/api";
import type { Profile } from "@/lib/profile";

/** 공개 프로필: 닉네임·소개·관심 태그만 (서버가 그 항목만 준다). 로그인한 회원만 본다. */
export function Detail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <PublicProfile id={id} />}</AuthGate>;
}

function PublicProfile({ id }: { id: string }) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api<Profile>(`/api/profiles/${id}`)
      .then((res) => active && setProfile(res.data))
      .catch((e: unknown) => {
        if (!active) return;
        setFailure(e instanceof ApiError && e.status === 404 ? "탈퇴했거나 없는 회원이에요." : errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [id]);

  if (failure) {
    return (
      <>
        <p role="alert">{failure}</p>
        <Link href="/board" className="link">
          게시판으로
        </Link>
      </>
    );
  }
  if (!profile) return <p>불러오는 중…</p>;
  return (
    <>
      <h1>{profile.nickname}</h1>
      <p className="meta">공개 프로필</p>
      <section className="card">
        <h2>소개</h2>
        <p className="prewrap">{profile.bio ?? "아직 소개가 없어요."}</p>
        {profile.tags.length > 0 && (
          <>
            <h2>관심 태그</h2>
            <TagList tags={profile.tags} />
          </>
        )}
      </section>
    </>
  );
}
