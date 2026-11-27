"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useState, type FormEvent } from "react";

import { api, ApiError, errorMessage, type Me } from "@/lib/api";
import { CLUB_MODES, editHint, type Club, type ClubMode } from "@/lib/club";

/** 모임 신청·수정 전용 화면 (이름·소개·활동 장소·활동 방식). 수정은 생성자만 (근거는 서버 403). */
export function ClubForm({ me, clubId }: { me: Me; clubId?: string }) {
  const router = useRouter();
  const id = useId();
  const editing = clubId !== undefined;
  const [loaded, setLoaded] = useState<Club | null>(null);
  const [form, setForm] = useState({ name: "", intro: "", place: "", mode: "OFFLINE" as ClubMode });
  const [notice, setNotice] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!editing) return;
    api<Club>(`/api/clubs/${clubId}`)
      .then((res) => {
        if (!res.data.canEdit) {
          setBlocked("이 모임을 수정할 권한이 없어요.");
          return;
        }
        setLoaded(res.data);
        setForm({ name: res.data.name, intro: res.data.intro, place: res.data.place, mode: res.data.mode });
      })
      .catch((e: unknown) => setBlocked(errorMessage(e)));
  }, [editing, clubId]);

  if (me.demo) return <p role="alert">체험 중에는 모임을 신청할 수 없어요.</p>;
  if (blocked) {
    return (
      <>
        <p role="alert">{blocked}</p>
        <Link href="/me/clubs" className="link">
          내 모임으로
        </Link>
      </>
    );
  }
  if (editing && !loaded) return <p>불러오는 중…</p>;

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => {
    const value = e.target.value;
    setForm((f) => ({ ...f, [key]: value }));
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = editing
        ? await api<Club>(`/api/clubs/${clubId}`, { method: "PATCH", body: form })
        : await api<Club>("/api/clubs", { method: "POST", body: form });
      router.push(`/clubs/${data.id}`);
    } catch (e) {
      setNotice(
        e instanceof ApiError && e.code === "VALIDATION_FAILED"
          ? "이름(60자 이내)·소개(2000자 이내)·활동 장소(200자 이내)를 입력하고 활동 방식을 골라 주세요."
          : errorMessage(e),
      );
      setBusy(false);
    }
  }

  return (
    <>
      <Link href={editing ? `/clubs/${clubId}` : "/clubs"} className="link">
        ← {editing ? "모임으로 돌아가기" : "모임 목록"}
      </Link>
      <h1>{editing ? "모임 수정" : "모임 만들기 신청"}</h1>
      <p className="meta">{loaded ? editHint(loaded.status) : "운영자가 승인하면 모임 목록에 보이고 회원을 받을 수 있어요."}</p>
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor={`${id}-name`}>모임 이름</label>
          <input id={`${id}-name`} value={form.name} maxLength={60} required onChange={set("name")} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-intro`}>소개</label>
          <textarea id={`${id}-intro`} value={form.intro} maxLength={2000} rows={8} required onChange={set("intro")} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-place`}>활동 장소</label>
          <input id={`${id}-place`} value={form.place} maxLength={200} required onChange={set("place")} />
        </div>
        <fieldset className="field">
          <legend>활동 방식</legend>
          <div className="choices">
            {CLUB_MODES.map(([value, label]) => (
              <label key={value} className="choice">
                <input type="radio" name={`${id}-mode`} value={value} checked={form.mode === value} onChange={set("mode")} />
                {label}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="actions">
          <button type="submit" disabled={busy}>
            {editing ? "저장" : "신청"}
          </button>
        </div>
        {notice && <p role="alert">{notice}</p>}
      </form>
    </>
  );
}
