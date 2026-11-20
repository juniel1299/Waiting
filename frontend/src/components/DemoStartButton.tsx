"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { api, ApiError, errorMessage, formatWait } from "@/lib/api";

interface DemoStartButtonProps {
  label?: string;
  className?: string;
}

/** 로그인 없이 새 데모 체험을 만들고 체험 회차 상세로 간다. 다시 누르면 새 체험으로 바뀐다. */
export function DemoStartButton({ label = "데모 체험하기", className }: DemoStartButtonProps) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      // 다시 보내 체험이 둘 생겨도 무해하다: 먼저 생긴 체험은 아무도 쓰지 않고 만료 후 정리 작업이 지운다
      const { data } = await api<{ sessionId: number }>("/api/demo/start", { method: "POST", retryWhileWaking: true });
      router.push(`/sessions/${data.sessionId}`);
    } catch (e) {
      if (e instanceof ApiError && e.code === "TOO_MANY_ATTEMPTS") {
        const wait = e.retryAfterSeconds ? `${formatWait(e.retryAfterSeconds)} 후` : "잠시 후";
        setError(`체험 시작 요청이 너무 많아요. ${wait} 다시 시도해 주세요.`);
      } else {
        setError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" className={className} disabled={busy} onClick={start}>
        {label}
      </button>
      {error && <p role="alert">{error}</p>}
    </>
  );
}
