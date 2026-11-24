"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import { memberRoleLabel, type Club, type ClubMember } from "@/lib/club";

export function Members() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <MemberList clubId={id} />}</AuthGate>;
}

/**
 * 모임 회원 목록. 만든 사람만 모임 운영자 임명·회수와 생성자 넘기기를 한다 (근거는 서버, 다른 사람에게는 버튼이 없다).
 * 역할은 글자 배지로 보이고, 바뀐 결과는 role=status 로 알린다.
 */
function MemberList({ clubId }: { clubId: string }) {
  const [club, setClub] = useState<Club | null>(null);
  const [members, setMembers] = useState<ClubMember[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(
    () =>
      Promise.all([
        api<Club>(`/api/clubs/${clubId}`),
        api<ClubMember[]>(`/api/clubs/${clubId}/members`),
      ])
        .then(([c, m]) => {
          setClub(c.data);
          setMembers(m.data);
          setError(null);
        })
        .catch((e: unknown) => setError(errorMessage(e))),
    [clubId],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function act(run: () => Promise<unknown>, done: string) {
    setBusy(true);
    try {
      await run();
      setStatus(done);
    } catch (e) {
      setStatus(errorMessage(e));
    }
    setBusy(false);
    await load();
  }

  function transfer(m: ClubMember) {
    if (
      !window.confirm(
        `'${m.label}' 님에게 모임 생성자를 넘길까요? 넘긴 뒤에는 모임 운영자로 남고, 임명·회수·삭제는 새 생성자가 해요.`,
      )
    )
      return;
    void act(
      () =>
        api(`/api/clubs/${clubId}/transfer`, {
          method: "POST",
          body: { accountId: m.accountId },
        }),
      `${m.label} 님에게 생성자를 넘겼어요.`,
    );
  }

  if (error) {
    return (
      <>
        <p role="alert">{error}</p>
        <button type="button" className="secondary" onClick={() => void load()}>
          다시 시도
        </button>{" "}
        <Link href={`/clubs/${clubId}`} className="link">
          모임 소개로
        </Link>
      </>
    );
  }
  if (!club || !members) return <p>불러오는 중…</p>;
  const creator = club.mine;

  return (
    <>
      <Link href={`/clubs/${clubId}`} className="link">
        ← {club.name}
      </Link>
      <h1>회원 {members.length}명</h1>
      <p role="status" className="sr-only-when-empty">
        {status}
      </p>
      {creator && (
        <p className="meta">
          만든 사람만 모임 운영자를 임명·회수하고 생성자를 넘길 수 있어요.
        </p>
      )}
      <ul className="member-list">
        {members.map((m) => (
          <li
            key={m.accountId}
            className="member-row"
            data-testid="club-member"
          >
            <strong>{m.label}</strong>
            <span
              className={m.role === "MANAGER" ? "badge confirmed" : "badge"}
            >
              {memberRoleLabel(m)}
            </span>
            <span className="meta">가입 {formatSeoul(m.joinedAt)}</span>
            {creator && !m.creator && (
              <span className="member-actions">
                {m.role === "MEMBER" ? (
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      void act(
                        () =>
                          api(`/api/clubs/${clubId}/managers/${m.accountId}`, {
                            method: "PUT",
                          }),
                        `${m.label} 님을 모임 운영자로 임명했어요.`,
                      )
                    }
                  >
                    운영자 임명
                  </button>
                ) : (
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      void act(
                        () =>
                          api(`/api/clubs/${clubId}/managers/${m.accountId}`, {
                            method: "DELETE",
                          }),
                        `${m.label} 님의 모임 운영자 권한을 회수했어요.`,
                      )
                    }
                  >
                    운영자 회수
                  </button>
                )}
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => transfer(m)}
                >
                  생성자 넘기기
                </button>
              </span>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
