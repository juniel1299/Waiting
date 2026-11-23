"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useState, type FormEvent } from "react";

import { api, ApiError, errorMessage, type Me } from "@/lib/api";
import { BOARDS, canWriteBoard, type BoardKind, type Post } from "@/lib/board";
import { MentionTextarea } from "@/components/board/MentionTextarea";

/** 글쓰기·수정 전용 화면. 권한이 없으면 폼 대신 안내 (근거는 서버의 403). 본문은 텍스트와 줄바꿈만. */
export function PostForm({ kind, me, postId }: { kind: BoardKind; me: Me; postId?: string }) {
  const router = useRouter();
  const id = useId();
  const { path, title: boardTitle } = BOARDS[kind];
  const editing = postId !== undefined;
  const [loaded, setLoaded] = useState<Post | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!editing) return;
    api<Post>(`/api/posts/${postId}`)
      .then((res) => {
        if (!res.data.canEdit) {
          setBlocked("이 글을 수정할 권한이 없어요.");
          return;
        }
        setLoaded(res.data);
        setTitle(res.data.title);
        setBody(res.data.body ?? "");
      })
      .catch((e: unknown) => setBlocked(errorMessage(e)));
  }, [editing, postId]);

  if (!editing && !canWriteBoard(kind, me)) {
    return <p role="alert">이 게시판에 글을 쓸 권한이 없어요.</p>;
  }
  if (blocked) {
    return (
      <>
        <p role="alert">{blocked}</p>
        <Link href={path} className="link">
          {BOARDS[kind].listLabel}으로
        </Link>
      </>
    );
  }
  if (editing && !loaded) return <p>불러오는 중…</p>;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = editing
        ? await api<Post>(`/api/posts/${postId}`, { method: "PATCH", body: { title, body } })
        : await api<Post>(`/api/boards/${kind}/posts`, { method: "POST", body: { title, body } });
      router.push(`${path}/${data.id}`);
    } catch (e) {
      setNotice(e instanceof ApiError && e.code === "VALIDATION_FAILED" ? "제목(100자 이내)과 본문(5000자 이내)을 입력해 주세요." : errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <>
      <Link href={editing ? `${path}/${postId}` : path} className="link">
        ← {editing ? "글로 돌아가기" : BOARDS[kind].listLabel}
      </Link>
      <h1>
        {boardTitle} · {editing ? "글 수정" : "글쓰기"}
      </h1>
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor={`${id}-title`}>제목</label>
          <input id={`${id}-title`} value={title} maxLength={100} required onChange={(e) => setTitle(e.target.value)} />
        </div>
        <MentionTextarea label="본문" value={body} onChange={setBody} maxLength={5000} rows={12} />
        <div className="actions">
          <button type="submit" disabled={busy}>
            {editing ? "저장" : "등록"}
          </button>
        </div>
        {notice && <p role="alert">{notice}</p>}
      </form>
    </>
  );
}
