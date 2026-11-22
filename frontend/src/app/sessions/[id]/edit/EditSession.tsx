"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { SessionForm } from "@/components/SessionForm";
import { api, errorMessage, type StudySession } from "@/lib/api";

/** 회차 수정 전용 화면 (제목·시작·장소). 생성자만, 시작 전만. 권한의 근거는 서버(PATCH 403·409)이고 화면은 canManage 를 따른다. */
export function EditSession() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <EditForm sessionId={id} />}</AuthGate>;
}

function EditForm({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const [session, setSession] = useState<StudySession | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<StudySession>(`/api/sessions/${sessionId}`)
      .then((res) => setSession(res.data))
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [sessionId]);

  return (
    <>
      <Link href="/me/sessions" className="link">
        ← 내가 만든 회차
      </Link>
      <h1>회차 수정</h1>
      {error && <p role="alert">{error}</p>}
      {!error && !session && <p>불러오는 중…</p>}
      {session && !session.canManage && <p role="alert">이 회차를 수정할 권한이 없어요.</p>}
      {session?.canManage && session.status === "STARTED" && (
        <p role="alert">이미 시작된 회차는 수정할 수 없어요.</p>
      )}
      {session?.canManage && session.status !== "STARTED" && (
        <>
          <p className="meta">
            정원 변경·명단·삭제는{" "}
            <Link href={`/sessions/${session.id}/manage`} className="link">
              회차 관리
            </Link>
            에서 해요.
          </p>
          <SessionForm submitLabel="수정 저장" session={session} onSaved={(s) => router.push(`/sessions/${s.id}`)} />
        </>
      )}
    </>
  );
}
