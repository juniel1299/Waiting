"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from "react";

import { api, errorMessage, formatSeoul, myReservationLabel, STATUS_LABEL, type SessionPage, type StudySession } from "@/lib/api";
import {
  apiQuery,
  FIELDS,
  pageWindow,
  parseSearch,
  rememberListHref,
  searchHref,
  SORTS,
  STATUSES,
  withChange,
  type Scope,
  type SearchState,
} from "@/lib/sessionSearch";

interface SessionSearchListProps {
  scope: Scope;
  /** 이 목록의 주소 (/sessions, /me/sessions) */
  path: string;
  /** 카드 아래 추가 링크 (내가 만든 회차의 관리·수정). 참가자 목록에는 없다 */
  cardActions?: (session: StudySession) => ReactNode;
  emptyText: string;
}

/**
 * 전체 회차·내가 만든 회차 공통 검색 목록. 주소가 조건의 원본이다: 조건을 바꾸면 주소를 바꾸고(1쪽부터), 주소가 바뀌면 다시 조회한다.
 * 상세에 갔다 와도(뒤로 가기·'← 목록') 같은 조건·쪽으로 돌아온다.
 */
export function SessionSearchList({ scope, path, cardActions, emptyText }: SessionSearchListProps) {
  const router = useRouter();
  const params = useSearchParams();
  const state = parseSearch(params);
  const href = searchHref(path, state);
  const [result, setResult] = useState<{ href: string; page: SessionPage } | null>(null);
  const [failure, setFailure] = useState<{ href: string; text: string } | null>(null);
  const seq = useRef(0);

  // 화면이 다시 보일 때(cacheComponents 복귀)도 이 효과가 다시 돌아 최신 목록을 받는다
  useEffect(() => {
    rememberListHref(href);
    const mine = ++seq.current;
    api<SessionPage>(`/api/sessions/search?${apiQuery(scope, parseSearch(new URL(href, "http://x").searchParams))}`)
      .then((res) => mine === seq.current && setResult({ href, page: res.data }))
      .catch((e: unknown) => mine === seq.current && setFailure({ href, text: errorMessage(e) }));
  }, [href, scope]);

  const go = (next: SearchState) => router.push(searchHref(path, next), { scroll: false });
  const reset = () => router.push(path, { scroll: false });
  const page = result?.href === href ? result.page : null;
  const error = failure?.href === href ? failure.text : null;
  const filtered = href !== path;

  return (
    <>
      {/* 주소가 바뀌면(뒤로 가기 포함) 입력 칸도 주소 값으로 다시 맞춘다 */}
      <SearchForm key={href} state={state} filtered={filtered} onSearch={go} onReset={reset} />
      {error && <p role="alert">{error}</p>}
      <p className="result-count" aria-live="polite">
        {page ? `${page.totalElements}개 회차` : error ? "" : "불러오는 중…"}
      </p>
      {page && page.totalElements === 0 && (
        <div className="empty">
          <p>{filtered ? "조건에 맞는 회차가 없어요." : emptyText}</p>
          {filtered && (
            <button type="button" className="secondary" onClick={reset}>
              조건 초기화
            </button>
          )}
        </div>
      )}
      {page && page.items.length === 0 && page.totalElements > 0 && (
        <p>
          이 쪽에는 회차가 없어요.{" "}
          <Link href={searchHref(path, withChange(state, { page: page.totalPages }))} className="link">
            마지막 쪽으로
          </Link>
        </p>
      )}
      <ul className="cards">
        {page?.items.map((s) => (
          <SessionCard key={s.id} session={s} actions={cardActions?.(s)} />
        ))}
      </ul>
      {page && page.totalPages > 1 && <Pagination path={path} state={state} totalPages={page.totalPages} />}
    </>
  );
}

/** 목록 카드: 제목 · 시작 일시 · 장소 · 확정/정원 · 모집 상태 순서. */
function SessionCard({ session: s, actions }: { session: StudySession; actions?: ReactNode }) {
  return (
    <li className="card">
      <Link href={`/sessions/${s.id}`} className="title">
        {s.title}
      </Link>
      <dl className="card-facts">
        <dt>시작</dt>
        <dd>{formatSeoul(s.startsAt)}</dd>
        <dt>장소</dt>
        <dd>{s.location}</dd>
        <dt>확정</dt>
        <dd>
          {s.confirmedCount} / 정원 {s.capacity}
        </dd>
        <dt>상태</dt>
        <dd className="badges">
          <span className={`badge status-${s.status.toLowerCase()}`}>{STATUS_LABEL[s.status]}</span>
          {s.waitingCount > 0 && <span className="badge">대기 {s.waitingCount}명</span>}
          {s.myReservation && <span className="badge confirmed">내 신청: {myReservationLabel(s.myReservation)}</span>}
        </dd>
      </dl>
      {actions && <div className="card-actions">{actions}</div>}
    </li>
  );
}

interface SearchFormProps {
  state: SearchState;
  filtered: boolean;
  onSearch: (next: SearchState) => void;
  onReset: () => void;
}

/** 검색어·날짜는 '검색'(Enter)으로, 고르는 칸(범위·상태·정렬)은 고르자마자 적용한다. 어느 쪽이든 1쪽부터. */
function SearchForm({ state, filtered, onSearch, onReset }: SearchFormProps) {
  const id = useId();
  const [q, setQ] = useState(state.q);
  const [from, setFrom] = useState(state.from);
  const [to, setTo] = useState(state.to);
  const typed = { q: q.trim(), from, to };
  const pick = (change: Partial<SearchState>) => onSearch(withChange(state, { ...typed, ...change }));

  function submit(event: FormEvent) {
    event.preventDefault();
    onSearch(withChange(state, typed));
  }

  return (
    <form role="search" aria-label="회차 검색" className="search-form" onSubmit={submit}>
      <div className="search-row">
        <div className="field">
          <label htmlFor={`${id}-field`}>검색 범위</label>
          <select id={`${id}-field`} value={state.field} onChange={(e) => pick({ field: e.target.value as SearchState["field"] })}>
            {FIELDS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="field grow">
          <label htmlFor={`${id}-q`}>검색어</label>
          <input id={`${id}-q`}
            type="search"
            value={q}
            maxLength={100}
            placeholder="제목이나 장소 일부"
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
      </div>
      <div className="search-row">
        <div className="field">
          <label htmlFor={`${id}-from`}>시작일부터</label>
          <input id={`${id}-from`} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-to`}>시작일까지</label>
          <input id={`${id}-to`} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-status`}>모집 상태</label>
          <select id={`${id}-status`} value={state.status} onChange={(e) => pick({ status: e.target.value as SearchState["status"] })}>
            {STATUSES.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-sort`}>정렬</label>
          <select id={`${id}-sort`} value={state.sort} onChange={(e) => pick({ sort: e.target.value as SearchState["sort"] })}>
            {SORTS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="actions">
        <button type="submit">검색</button>
        <button type="button" className="secondary" onClick={onReset} disabled={!filtered && !q && !from && !to}>
          초기화
        </button>
      </div>
    </form>
  );
}

interface PaginationProps {
  path: string;
  state: SearchState;
  totalPages: number;
}

function Pagination({ path, state, totalPages }: PaginationProps) {
  const to = (page: number) => searchHref(path, withChange(state, { page }));
  return (
    <nav aria-label="쪽 이동" className="pagination">
      {state.page > 1 ? (
        <Link href={to(state.page - 1)} scroll={false}>
          이전
        </Link>
      ) : (
        <span aria-disabled="true">이전</span>
      )}
      {pageWindow(state.page, totalPages).map((p) =>
        p === state.page ? (
          <span key={p} aria-current="page" className="current">
            {p}
          </span>
        ) : (
          <Link key={p} href={to(p)} scroll={false} aria-label={`${p}쪽`}>
            {p}
          </Link>
        ),
      )}
      {state.page < totalPages ? (
        <Link href={to(state.page + 1)} scroll={false}>
          다음
        </Link>
      ) : (
        <span aria-disabled="true">다음</span>
      )}
    </nav>
  );
}
