"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { CreateSessionForm } from "@/components/CreateSessionForm";

/** 회차 만들기 전용 화면. 만든 사람이 생성자가 되어 수정·정원·명단·삭제를 관리한다. */
export default function NewSessionPage() {
  const router = useRouter();
  return (
    <AuthGate>
      {(me) =>
        me.demo ? (
          <p role="alert">체험 계정은 회차를 만들 수 없어요. 가입한 계정으로 로그인해 주세요.</p>
        ) : (
          <>
            <Link href="/sessions" className="link">
              ← 회차 목록
            </Link>
            <h1>회차 만들기</h1>
            <CreateSessionForm submitLabel="회차 만들기" onCreated={(s) => router.push(`/sessions/${s.id}`)} />
          </>
        )
      }
    </AuthGate>
  );
}
