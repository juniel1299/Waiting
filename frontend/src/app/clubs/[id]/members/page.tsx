import { Suspense } from "react";

import { Members } from "./Members";

export default function ClubMembersPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Members />
    </Suspense>
  );
}
