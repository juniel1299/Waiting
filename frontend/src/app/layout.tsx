import type { Metadata } from "next";
import { Suspense } from "react";
import { ServerWakingNotice } from "@/components/ServerWakingNotice";
import { UiStoreProvider } from "@/lib/uiStore";

import "./globals.css";

export const metadata: Metadata = {
  title: "다음자리",
  description: "오프라인 개발 스터디 회차 예약·취소 대기 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>
        {/* 주소(usePathname)를 읽으므로 정적 렌더와 분리한다 */}
        <Suspense fallback={null}>
          <ServerWakingNotice />
        </Suspense>
        <UiStoreProvider>{children}</UiStoreProvider>
      </body>
    </html>
  );
}
