"use client";

import { Suspense } from "react";

import { AuthGate } from "@/components/AuthGate";
import { SessionSearchList } from "@/components/SessionSearchList";

/** 전체 회차: 검색·필터·정렬·페이지네이션. 참가자 예약 동선이라 관리 링크는 두지 않는다 (내가 만든 회차에서). */
export default function SessionsPage() {
  return (
    <AuthGate>
      {() => (
        <>
          <h1>회차 목록</h1>
          <Suspense fallback={<p>불러오는 중…</p>}>
            <SessionSearchList scope="all" path="/sessions" emptyText="등록된 회차가 없어요." />
          </Suspense>
        </>
      )}
    </AuthGate>
  );
}
