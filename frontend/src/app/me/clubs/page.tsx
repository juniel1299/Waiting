"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { ClubCard } from "@/components/club/ClubCard";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import { stageHint, STATUS_LABELS, type Club } from "@/lib/club";

export default function MyClubsPage() {
  return <AuthGate>{(me) => (me.demo ? <p>체험 중에는 모임을 만들거나 가입할 수 없어요.</p> : <MyClubs />)}</AuthGate>;
}

/** 내 모임: 내가 낸 신청의 진행 단계(색+글자, 반려 사유)와 가입한 모임. */
function MyClubs() {
  const [data, setData] = useState<{ applications: Club[]; joined: Club[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    api<{ applications: Club[]; joined: Club[] }>("/api/me/clubs")
      .then((res) => {
        setData(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [attempt]);

  if (error) {
    return (
      <p role="alert">
        {error}{" "}
        <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
          다시 시도
        </button>
      </p>
    );
  }
  if (!data) return <p>불러오는 중…</p>;
  return (
    <>
      <div className="page-head">
        <h1>내 모임</h1>
        <Link href="/clubs/new" className="button-link">
          모임 만들기 신청
        </Link>
      </div>
      <h2>내가 낸 신청</h2>
      {data.applications.length === 0 ? (
        <p className="empty">아직 낸 신청이 없어요.</p>
      ) : (
        <ul className="cards">
          {data.applications.map((c) => (
            <li key={c.id} className="card" data-testid="club-application">
              <Link href={`/clubs/${c.id}`} className="title">
                {c.name}
              </Link>
              <span className="stage">
                <span className={`badge club-${c.status.toLowerCase()}`}>{STATUS_LABELS[c.status]}</span>
                <span className="meta">신청 {formatSeoul(c.submittedAt)}</span>
              </span>
              <span>{stageHint(c.status)}</span>
              {c.rejectReason && <span className="reject-reason">반려 사유: {c.rejectReason}</span>}
            </li>
          ))}
        </ul>
      )}
      <h2>가입한 모임</h2>
      {data.joined.length === 0 ? (
        <p className="empty">
          아직 가입한 모임이 없어요.{" "}
          <Link href="/clubs/promo" className="link">
            모임 둘러보기
          </Link>
        </p>
      ) : (
        <ul className="cards">
          {data.joined.map((c) => (
            <ClubCard key={c.id} club={c} />
          ))}
        </ul>
      )}
    </>
  );
}
