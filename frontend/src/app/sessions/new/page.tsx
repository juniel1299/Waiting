"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { SessionForm } from "@/components/SessionForm";

/** 회차 만들기 전용 화면. 만든 사람이 생성자가 되어 수정·정원·명단·삭제를 관리한다 (내가 만든 회차). */
export default function NewSessionPage() {
  const router = useRouter();
  return (
    <AuthGate>
      {(me) =>
        me.demo ? (
          <p role="alert">체험 계정은 회차를 만들 수 없어요. 가입한 계정으로 로그인해 주세요.</p>
        ) : (
          <>
            <Link href="/me/sessions" className="link">
              ← 내가 만든 회차
            </Link>
            <h1>회차 만들기</h1>
            <p className="meta">정원은 만든 뒤 회차 관리에서 바꿀 수 있어요. 늘리면 대기자가 순서대로 확정돼요.</p>
            <SessionForm submitLabel="회차 만들기" onSaved={(s) => router.push(`/sessions/${s.id}`)} />
          </>
        )
      }
    </AuthGate>
  );
}
