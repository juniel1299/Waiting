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
  // 아바타는 사진 없이 닉네임 첫 글자(이모지·한글도 한 글자로)
  const initial = Array.from(profile.nickname)[0] ?? "?";
  return (
    <article className="profile">
      <header className="profile-head">
        <span className="avatar" aria-hidden="true">
          {initial}
        </span>
        <div className="profile-name">
          <h1>{profile.nickname}</h1>
          <p className="meta">공개 프로필</p>
        </div>
      </header>
      <section className="panel" aria-labelledby="profile-bio">
        <h2 id="profile-bio">자기소개</h2>
        {profile.bio ? <p className="prewrap profile-text">{profile.bio}</p> : <p className="meta">아직 소개가 없어요.</p>}
      </section>
      <section className="panel" aria-labelledby="profile-tags">
        <h2 id="profile-tags">관심 태그</h2>
        {profile.tags.length > 0 ? <TagList tags={profile.tags} /> : <p className="meta">아직 등록한 관심 태그가 없어요.</p>}
      </section>
    </article>
  );
}
