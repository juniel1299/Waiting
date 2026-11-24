import { Suspense } from "react";

import { Edit } from "./Edit";

export default function EditClubPostPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Edit />
    </Suspense>
  );
}
