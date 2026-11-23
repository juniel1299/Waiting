"use client";

import { useCallback, useEffect, useId, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import { modeLabel, type Club, type ClubPage } from "@/lib/club";

export default function OperatorClubsPage() {
  return (
    <AuthGate>
      {(me) => (me.role === "OPERATOR" ? <PendingClubs /> : <p role="alert">운영자만 볼 수 있어요.</p>)}
    </AuthGate>
  );
}

/** 승인 대기 신청: 화면에서 본 version 으로 승인·반려(사유 필수). 그 사이 신청자가 고쳤으면 409 안내 뒤 새로 읽는다. */
function PendingClubs() {
  const [page, setPage] = useState<ClubPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  const load = useCallback(
    () =>
      api<ClubPage>("/api/operator/clubs?status=PENDING&size=50")
        .then((res) => {
          setPage(res.data);
          setError(null);
        })
        .catch((e: unknown) => setError(errorMessage(e))),
    [],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function decide(club: Club, approve: boolean, reason?: string) {
    try {
      await api(`/api/operator/clubs/${club.id}/${approve ? "approve" : "reject"}`, {
        method: "POST",
        body: { version: club.version, reason },
      });
      setStatus(`'${club.name}' 신청을 ${approve ? "승인" : "반려"}했어요.`);
    } catch (e) {
      setStatus(
        e instanceof ApiError && e.code === "CLUB_CHANGED"
          ? `'${club.name}' 신청 내용이나 상태가 바뀌었어요. 새로 읽은 내용을 다시 확인해 주세요.`
          : errorMessage(e),
      );
    }
    await load();
  }

  return (
    <>
      <OperatorHead title="모임 승인" />
      <p role="status" className="sr-only-when-empty">
        {status}
      </p>
      {error && (
        <p role="alert">
          {error}{" "}
          <button type="button" className="secondary" onClick={() => void load()}>
            다시 시도
          </button>
        </p>
      )}
      {!page && !error && <p>불러오는 중…</p>}
      {page?.items.length === 0 && <p className="empty">승인을 기다리는 신청이 없어요.</p>}
      {page && page.items.length > 0 && (
        <ul className="cards">
          {page.items.map((c) => (
            <PendingCard key={`${c.id}-${c.version}`} club={c} onDecide={decide} />
          ))}
        </ul>
      )}
    </>
  );
}

function PendingCard({ club, onDecide }: { club: Club; onDecide: (c: Club, approve: boolean, reason?: string) => Promise<void> }) {
  const id = useId();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const run = async (approve: boolean) => {
    setBusy(true);
    await onDecide(club, approve, approve ? undefined : reason.trim());
    setBusy(false);
  };
  return (
    <li className="card" data-testid="pending-club">
      {/* .title 은 카드 전체를 덮는 링크용이라 여기 버튼을 가린다: 링크 없는 이름은 strong */}
      <strong>{club.name}</strong>
      <span className="meta">
        {modeLabel(club.mode)} · {club.place} · 신청 {club.creator.label} · {formatSeoul(club.submittedAt)}
      </span>
      <p className="club-intro">{club.intro}</p>
      <div className="field">
        <label htmlFor={`${id}-reason`}>반려 사유</label>
        <textarea id={`${id}-reason`} value={reason} maxLength={500} rows={2} onChange={(e) => setReason(e.target.value)} />
      </div>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => run(true)}>
          승인
        </button>
        <button type="button" className="secondary" disabled={busy || !reason.trim()} onClick={() => run(false)}>
          반려
        </button>
      </div>
    </li>
  );
}
