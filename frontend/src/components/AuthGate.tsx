"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";

import { api, ApiError, errorMessage, type Me } from "@/lib/api";

interface AuthGateProps {
  children: (me: Me) => ReactNode;
}

/** 로그인 사용자를 확인하고 머리글과 함께 화면을 그린다. 권한의 근거는 서버 응답이고 이건 화면용이다. */
export function AuthGate({ children }: AuthGateProps) {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api<Me>("/api/auth/me")
      .then((res) => active && setMe(res.data))
      .catch((e: unknown) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) {
          router.replace("/login");
          return;
        }
        setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [router]);

  if (error) {
    return (
      <main className="container">
        <p role="alert">{error}</p>
      </main>
    );
  }
  if (!me) {
    return <main className="container">불러오는 중…</main>;
  }
  return (
    <>
      <Header me={me} />
      <main className="container">{children(me)}</main>
    </>
  );
}

function Header({ me }: { me: Me }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  async function logout() {
    try {
      await api("/api/auth/logout", { method: "POST" });
      router.replace("/login");
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <header className="header">
      <Link href="/" className="brand">
        다음자리
      </Link>
      <nav className="nav">
        <Link href="/sessions">회차 목록</Link>
        {me.role === "PARTICIPANT" && <Link href="/me/reservations">내 신청</Link>}
        {!me.demo && <Link href="/me/sessions">내가 만든 회차</Link>}
        {!me.demo && <Link href="/sessions/new">회차 만들기</Link>}
        {me.role === "OPERATOR" && <Link href="/operator">회차 관리</Link>}
      </nav>
      <span className="who">{me.displayName} 님</span>
      {me.role === "PARTICIPANT" && !me.demo && (
        <Link href="/account/withdraw" className="link">
          회원 탈퇴
        </Link>
      )}
      <button type="button" className="secondary" onClick={logout}>
        로그아웃
      </button>
      {error && <p role="alert">{error}</p>}
    </header>
  );
}
