"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { FavoriteButton } from "@/components/FavoriteButton";
import { api, ApiError, errorMessage, formatSeoul, type Me } from "@/lib/api";
import { modeLabel, stageHint, STATUS_LABELS, type Club } from "@/lib/club";

/** 모임 소개. 가입·탈퇴·수정·신청 취소·삭제는 서버가 준 can* 를 따른다. 소개는 텍스트로만(줄바꿈 유지). */
export function ClubDetail({ clubId, me }: { clubId: string; me: Me }) {
  const router = useRouter();
  const [club, setClub] = useState<Club | null>(null);
  const [failure, setFailure] = useState<{ text: string; gone: boolean } | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    api<Club>(`/api/clubs/${clubId}`)
      .then((res) => {
        if (!active) return;
        setClub(res.data);
        setFailure(null);
      })
      .catch((e: unknown) => {
        if (!active) return;
        setFailure({ text: errorMessage(e), gone: e instanceof ApiError && e.status === 404 });
      });
    return () => {
      active = false;
    };
  }, [clubId, attempt]);

  async function act(run: () => Promise<unknown>, done: string) {
    setBusy(true);
    try {
      await run();
      setStatus(done);
      setAttempt((n) => n + 1);
    } catch (e) {
      setStatus(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!club || !window.confirm(`'${club.name}' 모임을 삭제할까요? 회원 ${club.memberCount}명의 가입 정보도 함께 지워지고 복구할 수 없어요.`)) return;
    setBusy(true);
    try {
      await api(`/api/clubs/${clubId}`, { method: "DELETE" });
      router.push(club.mine ? "/me/clubs" : "/clubs");
    } catch (e) {
      setStatus(errorMessage(e));
      setBusy(false);
    }
  }

  if (failure) {
    return (
      <>
        <p role="alert">{failure.gone ? "삭제되었거나 볼 수 없는 모임이에요." : failure.text}</p>
        {!failure.gone && (
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
            다시 시도
          </button>
        )}
        <Link href="/clubs" className="link">
          모임 목록으로
        </Link>
      </>
    );
  }
  if (!club) return <p>불러오는 중…</p>;

  return (
    <article className="club-detail">
      <Link href={club.status === "APPROVED" ? "/clubs" : "/me/clubs"} className="link">
        ← {club.status === "APPROVED" ? "모임 목록" : "내 모임"}
      </Link>
      <h1>{club.name}</h1>
      {club.status === "APPROVED" && !me.demo && <FavoriteButton kind="clubs" id={club.id} />}
      <p role="status" className="sr-only-when-empty">
        {status}
      </p>
      {club.status !== "APPROVED" && (
        <div className="stage">
          <span className={`badge club-${club.status.toLowerCase()}`}>{STATUS_LABELS[club.status]}</span>
          <span>{stageHint(club.status)}</span>
          {club.rejectReason && <p className="reject-reason">반려 사유: {club.rejectReason}</p>}
        </div>
      )}
      <dl className="facts">
        <dt>활동 방식</dt>
        <dd>{modeLabel(club.mode)}</dd>
        <dt>활동 장소</dt>
        <dd>{club.place}</dd>
        <dt>만든 사람</dt>
        <dd>{club.creator.label}</dd>
        <dt>회원</dt>
        <dd>{club.memberCount}명</dd>
        <dt>{club.status === "APPROVED" ? "승인" : "신청"}</dt>
        <dd>{formatSeoul(club.decidedAt && club.status === "APPROVED" ? club.decidedAt : club.submittedAt)}</dd>
      </dl>
      {club.status === "APPROVED" && (club.joined || me.role === "OPERATOR") && (
        <nav aria-label="모임 안 메뉴" className="tabs">
          <Link href={`/clubs/${clubId}/notices`}>모임 공지</Link>
          <Link href={`/clubs/${clubId}/board`}>모임 게시판</Link>
          {club.joined && <Link href={`/clubs/${clubId}/chat`}>채팅</Link>}
          <Link href={`/clubs/${clubId}/members`}>회원 {club.memberCount}명</Link>
          <Link href={`/clubs/${clubId}/roles`}>권한 관리</Link>
        </nav>
      )}
      {club.myRole && <p className="meta">내 역할: {club.mine ? "만든 사람 · 모임 운영자" : club.myRole === "MANAGER" ? "모임 운영자" : "회원"}</p>}
      <h2>소개</h2>
      <p className="club-intro">{club.intro}</p>
      <div className="actions">
        {club.canJoin && (
          <button type="button" disabled={busy} onClick={() => act(() => api(`/api/clubs/${clubId}/join`, { method: "POST" }), "모임에 가입했어요.")}>
            가입하기
          </button>
        )}
        {club.canLeave && (
          <button type="button" className="secondary" disabled={busy} onClick={() => act(() => api(`/api/clubs/${clubId}/leave`, { method: "POST" }), "모임에서 탈퇴했어요.")}>
            모임 탈퇴
          </button>
        )}
        {club.mine && club.status === "APPROVED" && (
          <span className="meta">만든 사람은 모임을 탈퇴할 수 없어요. 회원 목록에서 다른 회원에게 생성자를 넘기면 탈퇴할 수 있어요.</span>
        )}
        {club.canEdit && (
          <Link href={`/clubs/${clubId}/edit`} className="button-link secondary-link">
            {club.status === "REJECTED" || club.status === "CANCELLED" ? "고쳐서 다시 신청" : "수정"}
          </Link>
        )}
        {club.canCancel && (
          <button type="button" className="secondary" disabled={busy} onClick={() => act(() => api(`/api/clubs/${clubId}/cancel`, { method: "POST" }), "신청을 취소했어요.")}>
            신청 취소
          </button>
        )}
        {club.canDelete && (
          <button type="button" className="danger" disabled={busy} onClick={remove}>
            모임 삭제
          </button>
        )}
      </div>
    </article>
  );
}
