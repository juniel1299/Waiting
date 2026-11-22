"use client";

import Link from "next/link";
import { Suspense } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { SessionSearchList } from "@/components/SessionSearchList";

/** 운영자 전체 회차: 2단계 검색 목록을 그대로 쓰고, 카드마다 명단·삭제(회차 관리) 링크를 붙인다. */
export default function OperatorSessionsPage() {
  return (
    <AuthGate>
      {(me) =>
        me.role === "OPERATOR" ? (
          <>
            <OperatorHead title="전체 회차" />
            <Suspense fallback={<p>불러오는 중…</p>}>
              <SessionSearchList
                scope="all"
                path="/operator/sessions"
                emptyText="등록된 회차가 없어요."
                cardActions={(s) =>
                  s.canDelete && (
                    <Link href={`/sessions/${s.id}/manage`} className="link" aria-label={`${s.title} 명단·삭제`}>
                      명단·삭제
                    </Link>
                  )
                }
              />
            </Suspense>
          </>
        ) : (
          <p role="alert">권한이 없습니다.</p>
        )
      }
    </AuthGate>
  );
}
