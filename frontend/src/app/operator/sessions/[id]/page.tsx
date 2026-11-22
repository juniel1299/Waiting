import { Suspense } from "react";

import { Manage } from "../../../sessions/[id]/manage/Manage";

// 예전 주소(운영자 명단). 회차 관리 화면(/sessions/{id}/manage)과 같다.
export default function RosterPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Manage />
    </Suspense>
  );
}
