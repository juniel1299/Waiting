"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { api, ApiError, errorMessage, formatWait, isServerWaking, subscribeServerWaking } from "@/lib/api";

interface DemoStartButtonProps {
  label?: string;
  className?: string;
}

/**
 * 로그인 없이 새 데모 체험을 만들고 체험 회차 상세로 간다. 다시 누르면 새 체험으로 바뀐다.
 * 요청 중엔 disabled 대신 aria-disabled 로 막아 키보드 포커스를 유지하고, 실패하면 다시 시도할 수 있게 되살린다.
 */
export function DemoStartButton({ label = "데모 체험하기", className }: DemoStartButtonProps) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 같은 렌더 안의 연속 클릭(Enter·Space·클릭)도 한 번만 보내도록 상태와 별도로 즉시 막는다
  const inFlight = useRef(false);
  const waking = useSyncExternalStore(subscribeServerWaking, isServerWaking, () => false);

  // cacheComponents 는 떠난 화면을 숨긴 채 상태를 남긴다: 체험 화면으로 간 뒤 돌아오면 처음 상태로
  useEffect(
    () => () => {
      inFlight.current = false;
      setBusy(false);
      setFailed(false);
    },
    [],
  );

  async function start() {
    if (inFlight.current) return;
    inFlight.current = true;
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
      inFlight.current = false;
      setBusy(false);
      setFailed(true);
    }
  }

  const classes = [className, busy ? "loading" : null].filter(Boolean).join(" ") || undefined;
  return (
    <>
      <button type="button" className={classes} aria-busy={busy} aria-disabled={busy} onClick={start}>
        {busy ? "체험 준비 중…" : failed ? "다시 시도하기" : label}
      </button>
      {busy && (
        <p role="status">
          {waking ? "서버를 켜는 중이라 자동으로 다시 시도하고 있어요. 최대 5분쯤 걸릴 수 있어요." : "체험을 준비하고 있어요."}
        </p>
      )}
      {error && <p role="alert">{error}</p>}
    </>
  );
}
