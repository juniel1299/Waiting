"use client";

import type { ReactNode } from "react";

import { AuthGate } from "@/components/AuthGate";

/** 개발자 역할이 있을 때만 내용을 보인다(화면 편의, 판단은 서버 /api/dev 403). */
export function DevGate({ children }: { children: () => ReactNode }) {
  return (
    <AuthGate>
      {(me) =>
        !me.demo && me.roles?.includes("DEVELOPER") ? children() : <p role="alert">개발자만 볼 수 있어요. 필요하면 운영자에게 역할을 요청해 주세요.</p>
      }
    </AuthGate>
  );
}
