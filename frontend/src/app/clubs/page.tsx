"use client";

import { Suspense } from "react";

import { AuthGate } from "@/components/AuthGate";
import { ClubList } from "@/components/club/ClubList";

export default function ClubsPage() {
  return (
    <AuthGate>
      {(me) => (
        <Suspense fallback={<p>불러오는 중…</p>}>
          <ClubList me={me} />
        </Suspense>
      )}
    </AuthGate>
  );
}
