"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";

import { DemoStartButton } from "@/components/DemoStartButton";
import { api, errorMessage, type Me } from "@/lib/api";

export default function LoginPage() {
  return (
    <main className="container narrow">
      <h1>다음자리 로그인</h1>
      <Suspense>
        <LoginForm />
      </Suspense>
      <p className="meta">
        계정이 없나요?{" "}
        <Link href="/signup" className="link">
          회원가입
        </Link>
      </p>
      <p className="meta">계정이 없어도 체험할 수 있어요.</p>
      <DemoStartButton className="secondary" />
    </main>
  );
}

/** 가입 직후엔 ?signedUp=<아이디> 로 와서 안내와 함께 아이디를 채워 둔다. */
function LoginForm() {
  const router = useRouter();
  const signedUp = useSearchParams().get("signedUp");
  const [loginId, setLoginId] = useState(signedUp ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api<Me>("/api/auth/login", { method: "POST", body: { loginId, password } });
      router.replace(data.role === "OPERATOR" ? "/operator" : "/sessions");
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <>
      {signedUp && <p role="status">가입이 완료됐어요. 로그인해 주세요.</p>}
      <form onSubmit={submit} className="form">
        <label>
          아이디
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <button type="submit" disabled={busy}>
          로그인
        </button>
        {error && <p role="alert">{error}</p>}
      </form>
    </>
  );
}
