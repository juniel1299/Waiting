"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { api, errorMessage, type Me } from "@/lib/api";
import { boardApiQuery, boardHref, parseBoardSearch } from "@/lib/boardSearch";
import { type ClubPage } from "@/lib/club";
import { pageWindow } from "@/lib/sessionSearch";

import { ClubCard } from "./ClubCard";

/** 승인된 모임 목록: 이름·소개 검색과 쪽. 주소가 조건의 원본(게시판과 같은 방식). */
export function ClubList({ me }: { me: Me }) {
  const router = useRouter();
  const params = useSearchParams();
  const search = parseBoardSearch(params);
  const href = boardHref("/clubs", search);
  const [result, setResult] = useState<{ href: string; page: ClubPage } | null>(null);
  const [failure, setFailure] = useState<{ href: string; text: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const seq = useRef(0);
  const id = useId();
  const [q, setQ] = useState(search.q);

  useEffect(() => {
    const mine = ++seq.current;
    api<ClubPage>(`/api/clubs?${boardApiQuery(parseBoardSearch(new URL(href, "http://x").searchParams))}`)
      .then((res) => mine === seq.current && setResult({ href, page: res.data }))
      .catch((e: unknown) => mine === seq.current && setFailure({ href, text: errorMessage(e) }));
  }, [href, attempt]);

  const page = result?.href === href ? result.page : null;
  const error = failure?.href === href ? failure.text : null;
  function submit(event: FormEvent) {
    event.preventDefault();
    router.push(boardHref("/clubs", { ...search, q: q.trim(), page: 1 }), { scroll: false });
  }
  const to = (p: number) => boardHref("/clubs", { ...search, page: p });

  return (
    <>
      <div className="page-head">
        <h1>모임 목록</h1>
        {!me.demo && (
          <Link href="/clubs/new" className="button-link">
            모임 만들기 신청
          </Link>
        )}
      </div>
      {me.demo && <p className="meta">체험 중에는 승인된 모임 소개를 보기만 할 수 있어요.</p>}
      <form role="search" aria-label="모임 검색" className="search-form" onSubmit={submit}>
        <div className="search-row">
          <div className="field grow">
            <label htmlFor={`${id}-q`}>모임 이름·소개 검색</label>
            <input id={`${id}-q`} type="search" value={q} maxLength={100} onChange={(e) => setQ(e.target.value)} />
          </div>
        </div>
        <div className="actions">
          <button type="submit">검색</button>
          <button
            type="button"
            className="secondary"
            disabled={!search.q && !q}
            onClick={() => {
              setQ("");
              router.push("/clubs", { scroll: false });
            }}
          >
            초기화
          </button>
        </div>
      </form>
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
            다시 시도
          </button>
        </p>
      )}
      <p className="result-count" aria-live="polite">
        {page ? `${page.totalElements}개 모임` : error ? "" : "불러오는 중…"}
      </p>
      {page?.totalElements === 0 && (
        <div className="empty">
          <p>{search.q ? "조건에 맞는 모임이 없어요." : "아직 승인된 모임이 없어요."}</p>
        </div>
      )}
      {page && page.items.length > 0 && (
        <ul className="cards">
          {page.items.map((c) => (
            <ClubCard key={c.id} club={c} />
          ))}
        </ul>
      )}
      {page && page.totalPages > 1 && (
        <nav aria-label="쪽 이동" className="pagination">
          {pageWindow(search.page, page.totalPages).map((p) =>
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
        </nav>
      )}
    </>
  );
}

/** 모임 홍보: 회원이 많은 승인 모임 소개를 둘러본다. */
export function ClubPromo({ me }: { me: Me }) {
  const [page, setPage] = useState<ClubPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    api<ClubPage>("/api/clubs?sort=members&size=12")
      .then((res) => {
        setPage(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [attempt]);
  return (
    <>
      <h1>모임 홍보</h1>
      <p>함께 공부할 모임을 찾아보세요. 마음에 드는 모임은 소개에서 바로 가입할 수 있어요.</p>
      <p className="actions">
        <Link href="/clubs" className="button-link">
          모든 모임 보기·검색
        </Link>
        {!me.demo && (
          <Link href="/clubs/new" className="link">
            우리 모임 만들기 신청
          </Link>
        )}
      </p>
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
            다시 시도
          </button>
        </p>
      )}
      {!page && !error && <p>불러오는 중…</p>}
      {page?.items.length === 0 && <p className="empty">아직 소개할 모임이 없어요.</p>}
      {page && page.items.length > 0 && (
        <ul className="cards promo">
          {page.items.map((c) => (
            <ClubCard key={c.id} club={c} />
          ))}
        </ul>
      )}
    </>
  );
}
