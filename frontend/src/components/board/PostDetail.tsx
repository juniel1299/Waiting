"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useSyncExternalStore } from "react";

import { api, ApiError, errorMessage, formatSeoul, type Me } from "@/lib/api";
import { Comments } from "@/components/board/Comments";
import { MentionText } from "@/components/board/MentionText";
import { boardInfo, kindOf, lastBoardList, type BoardKind, type Post } from "@/lib/board";
import { MentionClub } from "@/components/board/MentionTextarea";

const noSubscribe = () => () => {};

/**
 * 글 상세. 본문은 React 텍스트 노드로만 그리고(white-space: pre-wrap 으로 줄바꿈 유지) HTML 로 해석하지 않는다.
 * 수정·삭제 버튼은 서버가 준 canEdit·canDelete 를 따른다. 삭제·없는 글은 안내와 목록 링크.
 */
export function PostDetail({ kind, postId, me, clubId }: { kind: BoardKind; postId: string; me: Me | null; clubId?: string }) {
  const router = useRouter();
  const { path, listLabel } = boardInfo(kind, clubId);
  const listHref = useSyncExternalStore(noSubscribe, () => lastBoardList(kind, clubId), () => path);
  const [post, setPost] = useState<Post | null>(null);
  const [failure, setFailure] = useState<{ text: string; gone: boolean } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    api<Post>(`/api/posts/${postId}`)
      .then((res) => {
        if (!active) return;
        // 다른 게시판의 글 번호로 들어오면 그 게시판 주소로 보낸다
        if (kindOf(res.data.board) !== kind || String(res.data.clubId ?? "") !== String(clubId ?? "")) {
          router.replace(`${boardInfo(kindOf(res.data.board), res.data.clubId).path}/${postId}`);
          return;
        }
        setPost(res.data);
        setFailure(null);
      })
      .catch((e: unknown) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) {
          router.replace("/login");
          return;
        }
        setFailure({ text: errorMessage(e), gone: e instanceof ApiError && e.status === 404 });
      });
    return () => {
      active = false;
    };
  }, [postId, kind, clubId, router, attempt]);

  async function remove() {
    const comments = post?.commentCount ? ` 댓글·답글 ${post.commentCount}개도 함께 삭제되고` : "";
    if (!post || !window.confirm(`'${post.title}' 글을 삭제할까요?${comments} 삭제하면 복구할 수 없어요.`)) return;
    setBusy(true);
    try {
      await api(`/api/posts/${post.id}`, { method: "DELETE" });
      router.replace(listHref);
    } catch (e) {
      setFailure({ text: errorMessage(e), gone: e instanceof ApiError && e.status === 404 });
      setBusy(false);
    }
  }

  if (failure && (failure.gone || !post)) {
    return (
      <>
        <p role="alert">{failure.text}</p>
        {failure.gone ? (
          <Link href={listHref} className="link">
            {listLabel}으로
          </Link>
        ) : (
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
            다시 시도
          </button>
        )}
      </>
    );
  }
  if (!post) return <p>불러오는 중…</p>;

  return (
    <article className="post">
      <Link href={listHref} className="link">
        ← {listLabel}
      </Link>
      <h1>{post.title}</h1>
      <p className="meta" data-testid="post-meta">
        {post.author.label} · {formatSeoul(post.createdAt)}
        {post.edited && post.updatedAt && <> · 수정됨({formatSeoul(post.updatedAt)})</>}
      </p>
      <div className="post-body" data-testid="post-body">
        <MentionText body={post.body ?? ""} mentions={post.mentions} />
      </div>
      {(post.canEdit || post.canDelete) && (
        <div className="actions">
          {post.canEdit && (
            <Link href={`${path}/${post.id}/edit`} className="button-link secondary-link">
              수정
            </Link>
          )}
          {post.canDelete && (
            <button type="button" className="danger" onClick={remove} disabled={busy}>
              삭제
            </button>
          )}
        </div>
      )}
      {failure && <p role="alert">{failure.text}</p>}
      <MentionClub.Provider value={post.clubId}>
        <Comments postId={post.id} me={me} />
      </MentionClub.Provider>
    </article>
  );
}
