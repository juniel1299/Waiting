import { Suspense } from "react";

import { Detail } from "./Detail";

export default function ClubPostPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Detail />
    </Suspense>
  );
}
