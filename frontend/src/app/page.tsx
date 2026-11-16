"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { DemoStartButton } from "@/components/DemoStartButton";
import { api, ApiError, type Me } from "@/lib/api";

/** 로그인한 사용자는 역할별 첫 화면으로, 아니면 소개와 데모 체험·로그인 입구를 보여준다. */
export default function Home() {
  const router = useRouter();
  const [anonymous, setAnonymous] = useState(false);

  useEffect(() => {
    api<Me>("/api/auth/me")
      .then(({ data }) => router.replace(data.role === "OPERATOR" ? "/operator" : "/sessions"))
      .catch((e: unknown) => setAnonymous(e instanceof ApiError && e.status === 401));
  }, [router]);

  if (!anonymous) {
    return <main className="container">불러오는 중…</main>;
  }
  return (
    <main className="container narrow">
      <h1>다음자리</h1>
      <p>정원이 있는 오프라인 개발 스터디의 회차 예약과 취소 대기 서비스예요.</p>
      <p className="meta">
        가입 없이 체험해 보세요. 마감된 샘플 회차에 대기를 걸고, 샘플 참가자가 취소하면 내 대기가 자동으로 확정되는
        과정을 볼 수 있어요.
      </p>
      <DemoStartButton />
      <Link href="/login" className="link">
        계정으로 로그인
      </Link>
    </main>
  );
}
