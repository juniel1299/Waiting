import { Suspense } from "react";

import { New } from "./New";

export default function NewClubPostPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <New />
    </Suspense>
  );
}
