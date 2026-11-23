"use client";

import { useEffect, useId, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage } from "@/lib/api";

/** 내 정보: 게시판·멘션에 보이는 공개 닉네임 (로그인 아이디와 별개, 중복 허용). */
export default function ProfilePage() {
  return (
    <AuthGate>
      {(me) => (me.demo ? <p role="alert">체험 계정은 닉네임을 정할 수 없어요.</p> : <NicknameForm />)}
    </AuthGate>
  );
}

function NicknameForm() {
  const id = useId();
  const [nickname, setNickname] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ nickname: string }>("/api/me/profile")
      .then((res) => setNickname(res.data.nickname))
      .catch((e: unknown) => setNotice({ kind: "alert", text: errorMessage(e) }));
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = await api<{ nickname: string }>("/api/me/profile", { method: "PATCH", body: { nickname } });
      setNickname(data.nickname);
      setNotice({ kind: "status", text: `닉네임을 '${data.nickname}'(으)로 바꿨어요.` });
    } catch (e) {
      setNotice({
        kind: "alert",
        text:
          e instanceof ApiError && e.code === "VALIDATION_FAILED"
            ? "닉네임은 2~20자이고 @ [ ] # 기호와 '운영자'·'탈퇴한 회원'·'회원+숫자'는 쓸 수 없어요."
            : errorMessage(e),
      });
    }
    setBusy(false);
  }

  return (
    <>
      <h1>내 정보</h1>
      {nickname === null && !notice && <p>불러오는 중…</p>}
      {nickname !== null && (
        <form className="form narrow" onSubmit={submit}>
          <div className="field">
            <label htmlFor={`${id}-nick`}>공개 닉네임</label>
            <input id={`${id}-nick`} value={nickname} minLength={2} maxLength={20} required onChange={(e) => setNickname(e.target.value)} />
            <span className="meta">게시판 작성자와 멘션에 보이는 이름이에요. 로그인 아이디는 공개되지 않아요.</span>
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>
              저장
            </button>
          </div>
        </form>
      )}
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </>
  );
}
