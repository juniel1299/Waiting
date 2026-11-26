"use client";

import Link from "next/link";
import { useEffect, useId, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { TagList } from "@/components/TagList";
import { api, ApiError, errorMessage } from "@/lib/api";
import { parseTags, type Profile } from "@/lib/profile";

/** 내 정보 > 프로필: 공개 닉네임(중복 확인), 소개, 관심 태그. 로그인 아이디는 공개되지 않는다. */
export default function ProfilePage() {
  return (
    <AuthGate>
      {(me) => (me.demo ? <p role="alert">체험 계정은 프로필을 정할 수 없어요.</p> : <ProfileForm />)}
    </AuthGate>
  );
}

function ProfileForm() {
  const id = useId();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [nickname, setNickname] = useState("");
  const [bio, setBio] = useState("");
  const [tags, setTags] = useState("");
  const [check, setCheck] = useState<{ ok: boolean; text: string } | null>(null);
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  function show(p: Profile) {
    setProfile(p);
    setNickname(p.nickname);
    setBio(p.bio ?? "");
    setTags(p.tags.join(", "));
  }

  useEffect(() => {
    api<Profile>("/api/me/profile")
      .then((res) => show(res.data))
      .catch((e: unknown) => setNotice({ kind: "alert", text: errorMessage(e) }));
  }, []);

  async function checkNickname() {
    try {
      const { data } = await api<{ available: boolean; reason: string | null }>(
        `/api/me/nickname/check?value=${encodeURIComponent(nickname)}`,
      );
      setCheck({ ok: data.available, text: data.available ? "쓸 수 있는 닉네임이에요." : (data.reason ?? "쓸 수 없는 닉네임이에요.") });
    } catch (e) {
      setCheck({ ok: false, text: errorMessage(e) });
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = await api<Profile>("/api/me/profile", { method: "PATCH", body: { nickname, bio, tags: parseTags(tags) } });
      show(data);
      setCheck(null);
      setNotice({ kind: "status", text: `프로필을 저장했어요. 닉네임은 '${data.nickname}'이에요.` });
    } catch (e) {
      const fields = e instanceof ApiError ? e.fields : undefined;
      setNotice({ kind: "alert", text: fields ? Object.values(fields).join(" ") : errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <>
      <h1>프로필</h1>
      <p className="meta">다른 회원에게는 닉네임·소개·관심 태그만 보여요.</p>
      {profile === null && !notice && <p>불러오는 중…</p>}
      {profile !== null && (
        <form className="form narrow" onSubmit={submit}>
          <div className="field">
            <label htmlFor={`${id}-nick`}>공개 닉네임</label>
            <div className="inline-field">
              <input
                id={`${id}-nick`}
                value={nickname}
                minLength={2}
                maxLength={12}
                required
                aria-describedby={`${id}-nick-help ${id}-nick-check`}
                onChange={(e) => {
                  setNickname(e.target.value);
                  setCheck(null);
                }}
              />
              <button type="button" className="secondary" onClick={checkNickname}>
                중복 확인
              </button>
            </div>
            <span id={`${id}-nick-help`} className="meta">
              한글·영문·숫자·공백 2~12자. 운영자·관리자 같은 이름과 &apos;회원+숫자&apos;는 쓸 수 없어요.
            </span>
            <span id={`${id}-nick-check`} className={check?.ok ? "meta ok" : "meta danger"} aria-live="polite">
              {check?.text}
            </span>
          </div>
          <div className="field">
            <label htmlFor={`${id}-bio`}>소개</label>
            <textarea id={`${id}-bio`} value={bio} maxLength={300} rows={4} onChange={(e) => setBio(e.target.value)} />
            <span className="meta">{bio.length} / 300</span>
          </div>
          <div className="field">
            <label htmlFor={`${id}-tags`}>관심 태그</label>
            <input id={`${id}-tags`} value={tags} onChange={(e) => setTags(e.target.value)} aria-describedby={`${id}-tags-help`} />
            <span id={`${id}-tags-help`} className="meta">
              쉼표로 나눠 5개까지 (태그마다 20자 이하)
            </span>
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>
              {busy ? "저장 중…" : "저장"}
            </button>
            <Link href={`/profiles/${profile.id}`} className="link">
              공개 프로필 보기
            </Link>
          </div>
          {profile.tags.length > 0 && <TagList tags={profile.tags} />}
        </form>
      )}
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </>
  );
}
