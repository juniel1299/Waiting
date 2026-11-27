"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";

import { ThemeToggle } from "@/components/ThemeToggle";
import { DemoStartButton } from "@/components/DemoStartButton";
import { api, errorMessage, type Me } from "@/lib/api";
import { PROVIDER_LABEL, PROVIDERS, socialNotice, type ProviderPath } from "@/lib/social";

export default function LoginPage() {
  return (
    <main className="container narrow">
      <div className="page-tools">
        <ThemeToggle />
      </div>
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

/** 가입 직후엔 ?signedUp=<아이디> 로 와서 안내와 함께 아이디를 채워 둔다. 탈퇴 직후엔 ?withdrawn=1. */
function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const signedUp = params.get("signedUp");
  const withdrawn = params.get("withdrawn") === "1";
  const social = socialNotice(new URLSearchParams(params.toString()));
  const [enabled, setEnabled] = useState<Record<ProviderPath, boolean> | null>(null);
  const [loginId, setLoginId] = useState(signedUp ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // cacheComponents 는 떠난 화면을 숨긴 채 상태를 남긴다: 숨겨질 때 진행 중 표시와 비밀번호를 비운다
  useEffect(
    () => () => {
      setBusy(false);
      setPassword("");
    },
    [],
  );

  useEffect(() => {
    api<Record<ProviderPath, boolean>>("/api/auth/oauth/providers")
      .then((res) => setEnabled(res.data))
      .catch(() => setEnabled(null));
  }, []);

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
      {withdrawn && <p role="status">탈퇴가 완료됐어요. 그동안 이용해 주셔서 고마워요.</p>}
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
      {social && <p role={social.kind}>{social.text}</p>}
      {enabled && PROVIDERS.some((p) => enabled[p]) && (
        <div className="social-login">
          <p className="meta">계정을 연결해 둔 회원만 쓸 수 있어요.</p>
          {PROVIDERS.filter((p) => enabled[p]).map((p) => (
            <a key={p} href={`/api/auth/oauth/${p}/login/start`} className="button-link secondary-link">
              {PROVIDER_LABEL[p]}로 로그인
            </a>
          ))}
        </div>
      )}
    </>
  );
}
