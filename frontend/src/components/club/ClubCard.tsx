import Link from "next/link";

import { modeLabel, STATUS_LABELS, type Club } from "@/lib/club";

/** 모임 카드: 이름(링크)·방식·장소·회원 수·소개 첫 부분. 승인 전 상태는 글자 배지로. 소개는 텍스트로만. */
export function ClubCard({ club, showStatus = false }: { club: Club; showStatus?: boolean }) {
  return (
    <li className="card club-card" data-testid="club-card">
      <Link href={`/clubs/${club.id}`} className="title">
        {club.name}
      </Link>
      <span className="meta">
        {modeLabel(club.mode)} · {club.place} · 회원 {club.memberCount}명
      </span>
      <p className="club-intro-preview">{club.intro}</p>
      {(showStatus || club.joined) && (
        <span className="badges">
          {showStatus && <span className={`badge club-${club.status.toLowerCase()}`}>{STATUS_LABELS[club.status]}</span>}
          {club.joined && <span className="badge confirmed">가입함</span>}
        </span>
      )}
    </li>
  );
}
