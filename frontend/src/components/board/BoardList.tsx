"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { api, ApiError, errorMessage, formatSeoul, type Me } from "@/lib/api";
import {
  boardInfo,
  rememberBoardList,
  type BoardKind,
  type PostPage,
} from "@/lib/board";
import {
  BOARD_FIELDS,
  boardApiQuery,
  boardHref,
  parseBoardSearch,
  type BoardSearch,
} from "@/lib/boardSearch";
import { pageWindow } from "@/lib/sessionSearch";

/** 게시판 목록: 제목·작성자·작성일·댓글 수, 제목·본문 검색과 쪽. 주소가 조건의 원본(상세에 갔다 와도 유지). */
export function BoardList({
  kind,
  me,
  clubId,
}: {
  kind: BoardKind;
  me: Me | null;
  clubId?: string;
}) {
  const router = useRouter();
  const params = useSearchParams();
  const { path, title, api: listApi } = boardInfo(kind, clubId);
  const search = parseBoardSearch(params);
  const href = boardHref(path, search);
  const [result, setResult] = useState<{ href: string; page: PostPage } | null>(
    null,
  );
  const [failure, setFailure] = useState<{
    href: string;
    text: string;
    forbidden: boolean;
  } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const seq = useRef(0);

  useEffect(() => {
    rememberBoardList(kind, href, clubId);
    const mine = ++seq.current;
    api<PostPage>(
      `${listApi}?${boardApiQuery(parseBoardSearch(new URL(href, "http://x").searchParams))}`,
    )
      .then(
        (res) => mine === seq.current && setResult({ href, page: res.data }),
      )
      .catch(
        (e: unknown) =>
          mine === seq.current &&
          setFailure({
            href,
            text: errorMessage(e),
            forbidden: e instanceof ApiError && e.status === 403,
          }),
      );
  }, [href, kind, clubId, listApi, attempt]);

  const page = result?.href === href ? result.page : null;
  const error = failure?.href === href ? failure.text : null;
  // 권한이 없으면(모임 회원 아님 등) 다시 시도해도 같으므로 안내와 돌아갈 링크만 보인다
  const forbidden = failure?.href === href && failure.forbidden;
  const go = (next: BoardSearch) =>
    router.push(boardHref(path, next), { scroll: false });

  return (
    <>
      <div className="page-head">
        <h1>{title}</h1>
        {page?.canWrite && (
          <Link href={`${path}/new`} className="button-link">
            글쓰기
          </Link>
        )}
      </div>
      {me?.demo && kind === "community" && (
        <p className="meta">체험 중에는 글을 읽기만 할 수 있어요.</p>
      )}
      {clubId && (
        <p>
          <Link href={`/clubs/${clubId}`} className="link">
            ← 모임 소개
          </Link>
        </p>
      )}
      {!forbidden && (
        <SearchForm
          key={href}
          search={search}
          onSearch={go}
          onReset={() => router.push(path, { scroll: false })}
        />
      )}
      {error && (
        <p role="alert">
          {error}{" "}
          {!forbidden && (
            <button
              type="button"
              className="secondary"
              onClick={() => setAttempt((n) => n + 1)}
            >
              다시 시도
            </button>
          )}
        </p>
      )}
      <p className="result-count" aria-live="polite">
        {page ? `${page.totalElements}개 글` : error ? "" : "불러오는 중…"}
      </p>
      {page && page.totalElements === 0 && (
        <div className="empty">
          <p>{search.q ? "조건에 맞는 글이 없어요." : "아직 글이 없어요."}</p>
        </div>
      )}
      {page && page.items.length > 0 && (
        <ul className="post-list">
          {page.items.map((p) => (
            <li key={p.id} className="post-row">
              <Link href={`${path}/${p.id}`} className="post-title">
                {p.title}
              </Link>
              <span className="post-row-meta">
                <span>{p.author.label}</span>
                <span>{formatSeoul(p.createdAt)}</span>
                <span aria-label={`댓글 ${p.commentCount}개`}>
                  댓글 {p.commentCount}
                </span>
                {p.edited && <span>수정됨</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {page && page.totalPages > 1 && (
        <Pagination path={path} search={search} totalPages={page.totalPages} />
      )}
    </>
  );
}

function SearchForm({
  search,
  onSearch,
  onReset,
}: {
  search: BoardSearch;
  onSearch: (s: BoardSearch) => void;
  onReset: () => void;
}) {
  const id = useId();
  const [q, setQ] = useState(search.q);
  const [field, setField] = useState(search.field);
  function submit(event: FormEvent) {
    event.preventDefault();
    onSearch({ q: q.trim(), field, page: 1 });
  }
  return (
    <form
      role="search"
      aria-label="글 검색"
      className="search-form"
      onSubmit={submit}
    >
      <div className="search-row">
        <div className="field">
          <label htmlFor={`${id}-field`}>검색 범위</label>
          <select
            id={`${id}-field`}
            value={field}
            onChange={(e) => setField(e.target.value as BoardSearch["field"])}
          >
            {BOARD_FIELDS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="field grow">
          <label htmlFor={`${id}-q`}>검색어</label>
          <input
            id={`${id}-q`}
            type="search"
            value={q}
            maxLength={100}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
      </div>
      <div className="actions">
        <button type="submit">검색</button>
        <button
          type="button"
          className="secondary"
          onClick={onReset}
          disabled={
            !search.q && search.field === "ALL" && search.page === 1 && !q
          }
        >
          초기화
        </button>
      </div>
    </form>
  );
}

function Pagination({
  path,
  search,
  totalPages,
}: {
  path: string;
  search: BoardSearch;
  totalPages: number;
}) {
  const to = (page: number) => boardHref(path, { ...search, page });
  return (
    <nav aria-label="쪽 이동" className="pagination">
      {search.page > 1 ? (
        <Link href={to(search.page - 1)} scroll={false}>
          이전
        </Link>
      ) : (
        <span aria-disabled="true">이전</span>
      )}
      {pageWindow(search.page, totalPages).map((p) =>
        p === search.page ? (
          <span key={p} aria-current="page" className="current">
            {p}
          </span>
        ) : (
          <Link key={p} href={to(p)} scroll={false} aria-label={`${p}쪽`}>
            {p}
          </Link>
        ),
      )}
      {search.page < totalPages ? (
        <Link href={to(search.page + 1)} scroll={false}>
          다음
        </Link>
      ) : (
        <span aria-disabled="true">다음</span>
      )}
    </nav>
  );
}
