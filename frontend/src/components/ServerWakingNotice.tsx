"use client";

import { usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";

import { TourLink } from "@/components/TourLink";
import { isServerWaking, subscribeServerWaking } from "@/lib/api";

// 데모 버튼이 있는 화면은 버튼 아래 상태 영역에서 같은 안내를 보여준다 (DemoStartButton)
const PAGES_WITH_DEMO_BUTTON = new Set(["/", "/login"]);

/** 잠든 무료 서버가 깨는 동안(요청이 늦거나 다시 보내는 중) 화면 위에 띄우는 안내. */
export function ServerWakingNotice() {
  const waking = useSyncExternalStore(subscribeServerWaking, isServerWaking, () => false);
  const pathname = usePathname();
  if (!waking || PAGES_WITH_DEMO_BUTTON.has(pathname)) return null;
  return (
    <p role="status" className="container waking">
      서버를 깨우는 중이에요. 무료 서버라 2~3분쯤 걸릴 수 있어요. 자동으로 다시 시도하고 있어요. <TourLink />
    </p>
  );
}
