"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
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

/** 현재 위치는 aria-current 로 알린다. 운영자 메뉴는 하위 탭(/operator/...)에서도 현재 위치다. */
function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const pathname = usePathname();
  const current = pathname === href || (href === "/operator" && pathname.startsWith("/operator/"));
  return (
    <Link href={href} aria-current={current ? "page" : undefined}>
      {children}
    </Link>
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
      <nav className="nav" aria-label="주 메뉴">
        <NavLink href="/sessions">회차 목록</NavLink>
        {me.role === "PARTICIPANT" && <NavLink href="/me/reservations">내 신청</NavLink>}
        {!me.demo && <NavLink href="/me/sessions">내가 만든 회차</NavLink>}
        {!me.demo && <NavLink href="/sessions/new">회차 만들기</NavLink>}
        {me.role === "OPERATOR" && <NavLink href="/operator">운영자</NavLink>}
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
