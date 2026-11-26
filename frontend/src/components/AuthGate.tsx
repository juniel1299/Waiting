"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";

import { api, ApiError, errorMessage, type Me } from "@/lib/api";

import { Shell } from "./Shell";

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
  return <Shell me={me}>{children(me)}</Shell>;
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
      {me ? (
        <Shell me={me}>{children(me)}</Shell>
      ) : (
        <>
          <PublicHeader />
          <main className="container">{children(me)}</main>
        </>
      )}
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
        <Link href="/notices" aria-current={usePathname().startsWith("/notices") ? "page" : undefined}>
          공지사항
        </Link>
      </nav>
      <Link href="/login" className="link">
        로그인
      </Link>
    </header>
  );
}
