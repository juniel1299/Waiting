"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { api, errorMessage, type Me } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [loginId, setLoginId] = useState("");
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
    <main className="container narrow">
      <h1>다음자리 로그인</h1>
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
    </main>
  );
}
