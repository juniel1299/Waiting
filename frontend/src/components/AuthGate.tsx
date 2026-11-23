"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";

import { api, ApiError, errorMessage, type Me } from "@/lib/api";

interface AuthGateProps {
  children: (me: Me) => ReactNode;
}

/** 로그인 확인. 비회원(401)이면 redirect 가 참일 때 로그인으로 보내고, 아니면 anonymous 로 알린다. */
function useMe(redirect: boolean) {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api<Me>("/api/auth/me")
      .then((res) => active && setMe(res.data))
      .catch((e: unknown) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) {
          if (redirect) router.replace("/login");
          else setAnonymous(true);
          return;
        }
        setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [router, redirect]);

  return { me, anonymous, error };
}

/** 로그인 사용자를 확인하고 머리글과 함께 화면을 그린다. 권한의 근거는 서버 응답이고 이건 화면용이다. */
export function AuthGate({ children }: AuthGateProps) {
  const { me, error } = useMe(true);
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

/** 비회원도 볼 수 있는 화면(서비스 공지). 로그인했으면 평소 머리글, 아니면 공개 머리글과 함께 me=null 로 그린다. */
export function OptionalAuthGate({ children }: { children: (me: Me | null) => ReactNode }) {
  const { me, anonymous, error } = useMe(false);
  if (error) {
    return (
      <main className="container">
        <p role="alert">{error}</p>
      </main>
    );
  }
  if (!me && !anonymous) {
    return <main className="container">불러오는 중…</main>;
  }
  return (
    <>
      {me ? <Header me={me} /> : <PublicHeader />}
      <main className="container">{children(me)}</main>
    </>
  );
}

function PublicHeader() {
  return (
    <header className="header">
      <Link href="/" className="brand">
        다음자리
      </Link>
      <nav className="nav" aria-label="주 메뉴">
        <NavLink href="/notices">공지사항</NavLink>
      </nav>
      <Link href="/login" className="link">
        로그인
      </Link>
    </header>
  );
}

/** 현재 위치는 aria-current 로 알린다. 운영자 메뉴는 하위 탭(/operator/...)에서도 현재 위치다. */
function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const pathname = usePathname();
  // 하위 화면(/operator/..., /board/12, /notices/new)에서도 그 메뉴가 현재 위치다
  const current = pathname === href || (["/operator", "/board", "/notices"].includes(href) && pathname.startsWith(`${href}/`));
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
        <NavLink href="/board">게시판</NavLink>
        <NavLink href="/notices">공지사항</NavLink>
        {me.role === "PARTICIPANT" && <NavLink href="/me/reservations">내 신청</NavLink>}
        {!me.demo && <NavLink href="/me/sessions">내가 만든 회차</NavLink>}
        {!me.demo && <NavLink href="/sessions/new">회차 만들기</NavLink>}
        {me.role === "OPERATOR" && <NavLink href="/operator">운영자</NavLink>}
      </nav>
      <span className="who">{me.displayName} 님</span>
      {!me.demo && (
        <Link href="/me/profile" className="link">
          내 정보
        </Link>
      )}
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
