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
    <main className="container home">
      <h1>다음자리</h1>
      <p>
        정원이 찬 스터디도 놓치지 않게: 대기를 걸어 두면 취소로 빈자리가 생길 때 순서대로 자동 확정돼요. 가입 없이
        나만의 샘플 회차로 직접 눌러 볼 수 있어요.
      </p>
      <ol className="flow" aria-label="체험 흐름">
        <li>
          <strong>대기 신청</strong>
          <span>마감된 회차에 줄을 서요</span>
        </li>
        <li>
          <strong>빈자리 발생</strong>
          <span>확정자가 취소해요</span>
        </li>
        <li>
          <strong>예약 확정</strong>
          <span>첫 대기자가 자동으로, 새로고침 없이</span>
        </li>
      </ol>
      <section className="demo-action" aria-label="데모 체험">
        <DemoStartButton />
      </section>
      <Link href="/tour" className="link">
        1분 시연 보기 (영상·화면)
      </Link>
      <p className="auth-links">
        계정이 있나요?{" "}
        <Link href="/login" className="link">
          로그인
        </Link>{" "}
        ·{" "}
        <Link href="/signup" className="link">
          회원가입
        </Link>
      </p>
    </main>
  );
}
