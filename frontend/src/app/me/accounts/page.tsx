import { Suspense } from "react";

import { Accounts } from "./Accounts";

// cacheComponents: 주소의 ?linked·?error 를 읽는 클라이언트 컴포넌트는 Suspense 안에 둔다.
export default function SocialAccountsPage() {
  return (
    <Suspense fallback={<main className="container">불러오는 중…</main>}>
      <Accounts />
    </Suspense>
  );
}
