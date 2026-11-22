"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  ["/operator", "사용량"],
  ["/operator/sessions", "전체 회차"],
  ["/operator/accounts", "계정 관리"],
] as const;

/** 운영자 화면 탭. 현재 위치는 aria-current 로 알린다. */
export function OperatorTabs() {
  const pathname = usePathname();
  return (
    <nav aria-label="운영자 메뉴" className="tabs">
      {TABS.map(([href, label]) => (
        <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
          {label}
        </Link>
      ))}
    </nav>
  );
}

/** 운영자 화면 머리: 제목과 탭. 운영자가 아니면 안내만 (권한의 근거는 서버 403). */
export function OperatorHead({ title }: { title: string }) {
  return (
    <>
      <h1>운영자 · {title}</h1>
      <OperatorTabs />
    </>
  );
}
