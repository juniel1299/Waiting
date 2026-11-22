"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import {
  ACCOUNT_STATUS_LABEL,
  ACTION_LABEL,
  type AccountStatus,
  type AccountSummary,
  type ActionEntry,
  type Impact,
  type ModerationAction,
  type Paged,
} from "@/lib/operator";

/**
 * 운영자 계정 관리: 검색 → 조치할 계정 선택 → 영향 범위 확인 → 사유 입력 → 이용 정지·해제·강제탈퇴. 아래에 조치 기록.
 * 권한·보호 규칙(본인·마지막 운영자, 상태 충돌)의 근거는 서버이고 화면은 응답(can*)을 따른다.
 */
export default function OperatorAccountsPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "OPERATOR" ? <Accounts /> : <p role="alert">권한이 없습니다.</p>)}
    </AuthGate>
  );
}

interface Query {
  q: string;
  status: AccountStatus | "ALL";
  page: number;
}

function Accounts() {
  const [query, setQuery] = useState<Query>({ q: "", status: "ALL", page: 1 });
  const [result, setResult] = useState<Paged<AccountSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const params = new URLSearchParams({ status: query.status, page: String(query.page), size: "10" });
    if (query.q) params.set("q", query.q);
    api<Paged<AccountSummary>>(`/api/operator/accounts?${params}`)
      .then((res) => {
        setResult(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [query, version]);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  return (
    <>
      <OperatorHead title="계정 관리" />
      <AccountSearch query={query} onSearch={(q, status) => setQuery({ q, status, page: 1 })} />
      {error && <p role="alert">{error}</p>}
      <p className="result-count" aria-live="polite">
        {result ? `${result.totalElements}개 계정` : error ? "" : "불러오는 중…"}
      </p>
      <ul className="cards">
        {result?.items.map((a) => (
          <li key={a.id} className="card">
            <span className="title-text">{a.loginId ?? `탈퇴한 회원 #${a.id}`}</span>
            <dl className="card-facts">
              <dt>이름</dt>
              <dd>{a.displayName}</dd>
              <dt>상태</dt>
              <dd className="badges">
                <span className={`badge account-${a.status.toLowerCase()}`}>{ACCOUNT_STATUS_LABEL[a.status]}</span>
                {a.role === "OPERATOR" && <span className="badge">운영자</span>}
              </dd>
              <dt>가입</dt>
              <dd>{formatSeoul(a.createdAt)}</dd>
              <dt>활동</dt>
              <dd>
                신청 {a.activeReservations}건 · 만든 회차 {a.createdSessions}개
              </dd>
            </dl>
            {a.status !== "WITHDRAWN" && (
              <div className="card-actions">
                <button
                  type="button"
                  className="secondary"
                  aria-expanded={selected === a.id}
                  onClick={() => setSelected(selected === a.id ? null : a.id)}
                >
                  {selected === a.id ? "조치 닫기" : `${a.loginId} 조치`}
                </button>
              </div>
            )}
            {selected === a.id && <ActionPanel accountId={a.id} onDone={refresh} />}
          </li>
        ))}
      </ul>
      {result && result.totalPages > 1 && (
        <nav aria-label="계정 쪽 이동" className="pagination">
          <button
            type="button"
            className="secondary"
            disabled={query.page <= 1}
            onClick={() => setQuery({ ...query, page: query.page - 1 })}
          >
            이전
          </button>
          <span aria-current="page" className="current">
            {query.page} / {result.totalPages}
          </span>
          <button
            type="button"
            className="secondary"
            disabled={query.page >= result.totalPages}
            onClick={() => setQuery({ ...query, page: query.page + 1 })}
          >
            다음
          </button>
        </nav>
      )}
      <ActionLog version={version} />
    </>
  );
}

interface AccountSearchProps {
  query: Query;
  onSearch: (q: string, status: Query["status"]) => void;
}

function AccountSearch({ query, onSearch }: AccountSearchProps) {
  const [q, setQ] = useState(query.q);
  const [status, setStatus] = useState(query.status);
  function submit(event: FormEvent) {
    event.preventDefault();
    onSearch(q.trim(), status);
  }
  return (
    <form role="search" aria-label="계정 검색" className="search-form" onSubmit={submit}>
      <div className="search-row">
        <div className="field grow">
          <label htmlFor="account-q">아이디·이름</label>
          <input id="account-q" type="search" value={q} maxLength={100} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="account-status">계정 상태</label>
          <select id="account-status" value={status} onChange={(e) => setStatus(e.target.value as Query["status"])}>
            <option value="ALL">전체</option>
            <option value="ACTIVE">활성</option>
            <option value="SUSPENDED">정지</option>
            <option value="WITHDRAWN">탈퇴</option>
          </select>
        </div>
      </div>
      <div className="actions">
        <button type="submit">계정 검색</button>
      </div>
    </form>
  );
}

/** 영향 범위를 먼저 보여 주고, 사유를 받아 조치한다. 강제탈퇴는 한 번 더 확인한다. */
function ActionPanel({ accountId, onDone }: { accountId: number; onDone: () => void }) {
  const [impact, setImpact] = useState<Impact | null>(null);
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(
    () =>
      api<Impact>(`/api/operator/accounts/${accountId}/impact`)
        .then((res) => setImpact(res.data))
        .catch((e: unknown) => setNotice({ kind: "alert", text: errorMessage(e) })),
    [accountId],
  );

  useEffect(() => {
    load().then(() => heading.current?.focus());
  }, [load]);

  async function act(action: ModerationAction) {
    if (!impact) return;
    if (!reason.trim()) {
      setNotice({ kind: "alert", text: "조치 사유를 입력해 주세요." });
      return;
    }
    if (action === "FORCE_WITHDRAW" && !window.confirm(withdrawConfirm(impact))) return;
    const path = { SUSPEND: "suspend", UNSUSPEND: "unsuspend", FORCE_WITHDRAW: "withdraw" }[action];
    setBusy(true);
    try {
      await api(`/api/operator/accounts/${accountId}/${path}`, { method: "POST", body: { reason } });
      setNotice({ kind: "status", text: `${impact.label} 계정을 ${ACTION_LABEL[action]}했어요.` });
      setReason("");
      onDone();
      await load();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    setBusy(false);
  }

  if (!impact) {
    return notice ? <p role={notice.kind}>{notice.text}</p> : <p>영향 범위를 확인하는 중…</p>;
  }
  return (
    <section className="panel" aria-labelledby={`impact-${accountId}`}>
      <h2 id={`impact-${accountId}`} ref={heading} tabIndex={-1}>
        {impact.label} 조치
      </h2>
      <dl className="facts" data-testid="impact">
        <dt>지금 상태</dt>
        <dd>{ACCOUNT_STATUS_LABEL[impact.status]}</dd>
        <dt>시작 전 확정 예약</dt>
        <dd>
          {impact.upcomingConfirmed}건 (강제탈퇴 시 취소, 대기자 {impact.promotions}명 자동 확정)
        </dd>
        <dt>대기 신청</dt>
        <dd>{impact.waiting}건 (강제탈퇴 시 종료)</dd>
        <dt>시작된 회차 확정</dt>
        <dd>{impact.startedConfirmedKept}건 (참가 기록으로 남음)</dd>
        <dt>만든 회차</dt>
        <dd>
          {impact.ownedSessions}개와 그 신청 {impact.ownedSessionReservations}건 (강제탈퇴 시 함께 삭제, 복구 불가)
        </dd>
      </dl>
      <p className="meta">
        이용 정지는 신청·회차를 그대로 두고 로그인과 이미 로그인한 기기의 접근만 막아요. 해제하면 그대로 이어서 쓸 수 있어요.
      </p>
      {impact.blockedReason ? (
        <p role="alert">{impact.blockedReason}</p>
      ) : (
        <div className="form">
          <label>
            조치 사유 (필수, 500자 이내)
            <textarea value={reason} maxLength={500} rows={3} onChange={(e) => setReason(e.target.value)} />
          </label>
          <div className="actions wrap">
            {impact.canSuspend && (
              <button type="button" disabled={busy} onClick={() => act("SUSPEND")}>
                이용 정지
              </button>
            )}
            {impact.canUnsuspend && (
              <button type="button" disabled={busy} onClick={() => act("UNSUSPEND")}>
                정지 해제
              </button>
            )}
            {impact.canWithdraw && (
              <button type="button" className="danger" disabled={busy} onClick={() => act("FORCE_WITHDRAW")}>
                강제탈퇴
              </button>
            )}
          </div>
        </div>
      )}
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </section>
  );
}

function withdrawConfirm(impact: Impact): string {
  return (
    `${impact.label} 계정을 강제탈퇴할까요?\n` +
    `시작 전 확정 ${impact.upcomingConfirmed}건 취소(대기자 ${impact.promotions}명 확정), 대기 ${impact.waiting}건 종료, ` +
    `만든 회차 ${impact.ownedSessions}개와 그 신청 ${impact.ownedSessionReservations}건 삭제. ` +
    "계정은 익명화되고 되돌릴 수 없어요."
  );
}

/** 최근 운영 조치. 탈퇴한 계정은 '탈퇴한 회원 #id' 로 보인다. */
function ActionLog({ version }: { version: number }) {
  const [log, setLog] = useState<Paged<ActionEntry> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Paged<ActionEntry>>("/api/operator/actions?size=20")
      .then((res) => setLog(res.data))
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [version]);

  return (
    <section className="panel" aria-labelledby="action-log-heading">
      <h2 id="action-log-heading">최근 조치 기록</h2>
      {error && <p role="alert">{error}</p>}
      {log?.items.length === 0 && <p>아직 조치 기록이 없어요.</p>}
      {log && log.items.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">조치</th>
                <th scope="col">대상</th>
                <th scope="col">처리자</th>
                <th scope="col">사유</th>
                <th scope="col">처리</th>
              </tr>
            </thead>
            <tbody>
              {log.items.map((a) => (
                <tr key={a.id}>
                  <td>{formatSeoul(a.createdAt)}</td>
                  <td>{ACTION_LABEL[a.action]}</td>
                  <td>{a.targetLabel}</td>
                  <td>{a.operatorLabel}</td>
                  <td>{a.reason}</td>
                  <td>
                    {a.action === "FORCE_WITHDRAW"
                      ? `신청 ${a.cancelledReservations}건 취소 · 회차 ${a.deletedSessions}개 삭제`
                      : "-"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
