"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { AuthGate } from "@/components/AuthGate";

/** 역할에 맞는 첫 화면으로 보낸다 (미로그인은 AuthGate 가 /login 으로). */
export default function Home() {
  return <AuthGate>{(me) => <RoleRedirect to={me.role === "OPERATOR" ? "/operator" : "/sessions"} />}</AuthGate>;
}

function RoleRedirect({ to }: { to: string }) {
  const router = useRouter();
  useEffect(() => {
    router.replace(to);
  }, [router, to]);
  return null;
}
