"use client";

import Link from "next/link";
import { Suspense } from "react";

import { AuthGate } from "@/components/AuthGate";
import { SessionSearchList } from "@/components/SessionSearchList";
import type { StudySession } from "@/lib/api";

/** 내가 만든 회차: 전체 회차와 같은 검색 조건에 생성자 관리 링크(관리·수정)를 붙인다. */
export default function MySessionsPage() {
  return (
    <AuthGate>
      {(me) =>
        me.demo ? (
          <p role="alert">체험 계정은 회차를 만들 수 없어요. 가입한 계정으로 로그인해 주세요.</p>
        ) : (
          <>
            <div className="page-head">
              <h1>내가 만든 회차</h1>
              <Link href="/sessions/new" className="button-link">
                회차 만들기
              </Link>
            </div>
            <Suspense fallback={<p>불러오는 중…</p>}>
              <SessionSearchList
                scope="mine"
                path="/me/sessions"
                emptyText="아직 만든 회차가 없어요."
                cardActions={(s) => <OwnerLinks session={s} />}
              />
            </Suspense>
          </>
        )
      }
    </AuthGate>
  );
}

function OwnerLinks({ session }: { session: StudySession }) {
  return (
    <>
      {session.canManage && session.status !== "STARTED" && (
        <Link href={`/sessions/${session.id}/edit`} className="link" aria-label={`${session.title} 수정`}>
          수정
        </Link>
      )}
      {session.canDelete && (
        <Link href={`/sessions/${session.id}/manage`} className="link" aria-label={`${session.title} 관리`}>
          명단·정원·삭제
        </Link>
      )}
    </>
  );
}
