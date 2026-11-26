import { Suspense } from "react";

import { Members } from "../members/Members";

export default function ClubRolesPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Members manage />
    </Suspense>
  );
}
