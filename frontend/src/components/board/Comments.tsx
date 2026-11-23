"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { api, ApiError, errorMessage, formatSeoul, type Me } from "@/lib/api";
import { MentionText } from "@/components/board/MentionText";
import { MentionTextarea } from "@/components/board/MentionTextarea";

export interface CommentItem {
  id: number;
  postId: number;
  parentId: number | null;
  body: string;
  author: { label: string };
  mentions: { id: number; nickname: string }[];
  createdAt: string;
  updatedAt: string | null;
  edited: boolean;
  likeCount: number;
  liked: boolean;
  replyCount: number;
  canEdit: boolean;
  canDelete: boolean;
  replies: CommentItem[];
}

/** 댓글과 한 단계 답글. 쓰기·추천은 일반 회원·운영자만 (근거는 서버). 바뀐 뒤에는 서버에서 목록을 다시 읽는다. */
export function Comments({ postId, me }: { postId: number; me: Me | null }) {
  const [items, setItems] = useState<CommentItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const canWrite = !!me && !me.demo;

  const load = useCallback(
    () =>
      api<CommentItem[]>(`/api/posts/${postId}/comments`)
        .then((res) => {
          setItems(res.data);
          setError(null);
        })
        .catch((e: unknown) => setError(errorMessage(e))),
    [postId],
  );

  useEffect(() => {
    load();
  }, [load]);

  const done = (message: string) => {
    setStatus(message);
    return load();
  };

  const total = items?.reduce((n, c) => n + 1 + c.replies.length, 0) ?? 0;
  return (
    <section className="comments" aria-label="댓글 목록">
      <h2>댓글 {items ? `${total}개` : ""}</h2>
      <p role="status" className="sr-only-when-empty">
        {status}
      </p>
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={load}>
            다시 시도
          </button>
        </p>
      )}
      {!items && !error && <p>댓글을 불러오는 중…</p>}
      {items?.length === 0 && <p className="meta">아직 댓글이 없어요.</p>}
      <ul className="comment-list">
        {items?.map((c) => (
          <CommentView key={c.id} comment={c} canWrite={canWrite} onDone={done} postId={postId} />
        ))}
      </ul>
      {canWrite ? (
        <CommentForm postId={postId} onDone={done} label="댓글" submitLabel="댓글 등록" />
      ) : me?.demo ? (
        <p className="meta">체험 중에는 댓글을 읽기만 할 수 있어요.</p>
      ) : !me ? (
        <p className="meta">
          <Link href="/login" className="link">
            로그인
          </Link>
          한 회원만 댓글을 쓸 수 있어요.
        </p>
      ) : null}
    </section>
  );
}

interface CommentViewProps {
  comment: CommentItem;
  canWrite: boolean;
  postId: number;
  onDone: (message: string) => Promise<unknown>;
}

function CommentView({ comment: c, canWrite, postId, onDone }: CommentViewProps) {
  const [mode, setMode] = useState<"view" | "edit" | "reply">("view");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const isReply = c.parentId !== null;

  async function act(action: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await onDone(message);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? "삭제되었거나 찾을 수 없는 댓글이에요." : errorMessage(e));
      if (e instanceof ApiError && e.status === 404) await onDone("");
    }
    setBusy(false);
  }

  function remove() {
    const replies = c.replyCount > 0 ? ` 답글 ${c.replyCount}개도 함께 삭제되고` : "";
    if (!window.confirm(`이 ${isReply ? "답글" : "댓글"}을 삭제할까요?${replies} 복구할 수 없어요.`)) return;
    act(() => api(`/api/comments/${c.id}`, { method: "DELETE" }), `${isReply ? "답글" : "댓글"}을 삭제했어요.`);
  }

  return (
    <li className={isReply ? "comment reply" : "comment"} data-testid={isReply ? "reply" : "comment"}>
      <p className="comment-meta">
        {isReply && <span aria-hidden="true">↳ </span>}
        <strong>{c.author.label}</strong> · {formatSeoul(c.createdAt)}
        {c.edited && " · 수정됨"}
      </p>
      {mode === "edit" ? (
        <CommentForm
          postId={postId}
          commentId={c.id}
          initial={c.body}
          label={isReply ? "답글 수정" : "댓글 수정"}
          submitLabel="저장"
          onCancel={() => setMode("view")}
          onDone={async (m) => {
            setMode("view");
            await onDone(m);
          }}
        />
      ) : (
        <div className="comment-body">
          <MentionText body={c.body} mentions={c.mentions} />
        </div>
      )}
      <div className="comment-actions">
        <button
          type="button"
          className={c.liked ? "like liked" : "like secondary"}
          aria-pressed={c.liked}
          disabled={!canWrite || busy}
          onClick={() =>
            act(() => api(`/api/comments/${c.id}/like`, { method: c.liked ? "DELETE" : "PUT" }), c.liked ? "추천을 취소했어요." : "추천했어요.")
          }
        >
          {c.liked ? "추천함" : "추천"} {c.likeCount}
        </button>
        {canWrite && !isReply && mode !== "reply" && (
          <button type="button" className="secondary" onClick={() => setMode("reply")}>
            답글
          </button>
        )}
        {c.canEdit && mode === "view" && (
          <button type="button" className="secondary" onClick={() => setMode("edit")}>
            수정
          </button>
        )}
        {c.canDelete && (
          <button type="button" className="secondary danger-text" onClick={remove} disabled={busy}>
            삭제
          </button>
        )}
      </div>
      {error && <p role="alert">{error}</p>}
      {mode === "reply" && (
        <CommentForm
          postId={postId}
          parentId={c.id}
          label="답글"
          submitLabel="답글 등록"
          onCancel={() => setMode("view")}
          onDone={async (m) => {
            setMode("view");
            await onDone(m);
          }}
        />
      )}
      {c.replies.length > 0 && (
        <ul className="comment-list replies">
          {c.replies.map((r) => (
            <CommentView key={r.id} comment={r} canWrite={canWrite} postId={postId} onDone={onDone} />
          ))}
        </ul>
      )}
    </li>
  );
}

interface CommentFormProps {
  postId: number;
  parentId?: number;
  commentId?: number;
  initial?: string;
  label: string;
  submitLabel: string;
  onCancel?: () => void;
  onDone: (message: string) => Promise<unknown>;
}

function CommentForm({ postId, parentId, commentId, initial = "", label, submitLabel, onCancel, onDone }: CommentFormProps) {
  const [body, setBody] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (commentId) await api(`/api/comments/${commentId}`, { method: "PATCH", body: { body } });
      else await api(`/api/posts/${postId}/comments`, { method: "POST", body: { body, parentId: parentId ?? null } });
      setBody("");
      await onDone(commentId ? "수정했어요." : parentId ? "답글을 등록했어요." : "댓글을 등록했어요.");
    } catch (e) {
      setError(e instanceof ApiError && e.code === "VALIDATION_FAILED" ? "1000자 이내로 입력해 주세요." : errorMessage(e));
    }
    setBusy(false);
  }

  return (
    <form className="form comment-form" onSubmit={submit}>
      <MentionTextarea label={label} value={body} onChange={setBody} maxLength={1000} rows={3} />
      <div className="actions">
        <button type="submit" disabled={busy}>
          {submitLabel}
        </button>
        {onCancel && (
          <button type="button" className="secondary" onClick={onCancel}>
            취소
          </button>
        )}
      </div>
      {error && <p role="alert">{error}</p>}
    </form>
  );
}
