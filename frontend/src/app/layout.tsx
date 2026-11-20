import type { Metadata } from "next";
import { ServerWakingNotice } from "@/components/ServerWakingNotice";

import "./globals.css";

export const metadata: Metadata = {
  title: "다음자리",
  description: "오프라인 개발 스터디 회차 예약·취소 대기 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>
        <ServerWakingNotice />
        {children}
      </body>
    </html>
  );
}
