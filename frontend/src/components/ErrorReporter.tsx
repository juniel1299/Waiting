"use client";

import { useEffect } from "react";

import { api } from "@/lib/api";
import { createReporter } from "@/lib/errorReport";

const RELEASE = process.env.NEXT_PUBLIC_VERCEL_GIT_COMMIT_SHA?.slice(0, 12);

/** 처리하지 못한 화면 오류를 에러 로그로 보낸다(같은 오류 한 번, 화면당 5건). 보내기가 실패해도 화면에는 영향이 없다. */
export function ErrorReporter() {
  useEffect(() => {
    const report = createReporter((payload) => {
      api("/api/errors", { method: "POST", body: payload }).catch(() => undefined);
    });
    const onError = (e: ErrorEvent) => report(e.error ?? e.message, window.location.pathname, RELEASE);
    const onRejection = (e: PromiseRejectionEvent) => report(e.reason, window.location.pathname, RELEASE);
    window.addEventListener("error", onError);
    window.addEventListener("unhandledrejection", onRejection);
    return () => {
      window.removeEventListener("error", onError);
      window.removeEventListener("unhandledrejection", onRejection);
    };
  }, []);
  return null;
}
