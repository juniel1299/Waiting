import { Suspense } from "react";

import { Edit } from "./Edit";

// cacheComponents: 런타임 params 를 읽는 클라이언트 컴포넌트는 Suspense 안에 둔다.
export default function EditClubPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Edit />
    </Suspense>
  );
}
