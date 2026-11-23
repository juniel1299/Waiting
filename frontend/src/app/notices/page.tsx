"use client";

import { Suspense } from "react";

import { OptionalAuthGate } from "@/components/AuthGate";
import { BoardList } from "@/components/board/BoardList";

export default function BoardPage() {
  return (
    <OptionalAuthGate>
      {(me) => (
        <Suspense fallback={<p>불러오는 중…</p>}>
          <BoardList kind="notice" me={me} />
        </Suspense>
      )}
    </OptionalAuthGate>
  );
}
