"use client";

import { Suspense } from "react";

import { AuthGate } from "@/components/AuthGate";
import { BoardList } from "@/components/board/BoardList";

export default function BoardPage() {
  return (
    <AuthGate>
      {(me) => (
        <Suspense fallback={<p>불러오는 중…</p>}>
          <BoardList kind="community" me={me} />
        </Suspense>
      )}
    </AuthGate>
  );
}
