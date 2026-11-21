"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { DemoStartButton } from "@/components/DemoStartButton";
import { api, type Me } from "@/lib/api";

/**
 * 소개와 데모 체험·로그인 입구를 바로 보여준다. 서버가 깨는 중이어도 로그인 확인을 기다리지 않고,
 * 확인 응답이 로그인 상태면 그때 역할별 첫 화면으로 보낸다.
 */
export default function Home() {
  const router = useRouter();

  useEffect(() => {
    let active = true;
    api<Me>("/api/auth/me")
      .then(({ data }) => active && router.replace(data.role === "OPERATOR" ? "/operator" : "/sessions"))
      .catch(() => {
        // 로그인 전(401)이거나 서버 오류: 입구를 그대로 둔다
      });
    return () => {
      active = false;
    };
  }, [router]);

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
      <Link href="/signup" className="link">
        회원가입
      </Link>
    </main>
  );
}
