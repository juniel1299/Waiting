"use client";

import { useSearchParams } from "next/navigation";
import { useEffect, useId, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import { PROVIDER_LABEL, PROVIDERS, socialNotice, type ProviderPath } from "@/lib/social";
import { useUi } from "@/lib/uiStore";

interface MySocial {
  items: { provider: "KAKAO" | "NAVER"; linkedAt: string }[];
  providers: Record<ProviderPath, boolean>;
}

export function Accounts() {
  return <AuthGate>{(me) => (me.demo ? <p role="alert">체험 계정은 소셜 계정을 연결할 수 없어요.</p> : <SocialAccounts />)}</AuthGate>;
}

/** 내 정보 > 로그인 계정 연결: 카카오·네이버 연결(공급자 화면으로 이동)·해제(비밀번호 확인). 설정이 없으면 준비 중. */
function SocialAccounts() {
  const params = useSearchParams();
  const notice = socialNotice(new URLSearchParams(params.toString()));
  const [data, setData] = useState<MySocial | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    api<MySocial>("/api/me/social")
      .then((res) => {
        setData(res.data);
        setError(null);
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [attempt]);

  return (
    <>
      <h1>로그인 계정 연결</h1>
      <p className="meta">
        카카오·네이버 계정을 연결하면 그 계정으로도 로그인할 수 있어요. 아이디·비밀번호 로그인은 그대로 남아요. 공급자에게서는 회원
        식별 번호만 받고, 이메일·연락처는 받지 않아요.
      </p>
      {notice && <p role={notice.kind}>{notice.text}</p>}
      {error ? (
        <>
          <p role="alert">{error}</p>
          <button type="button" onClick={() => setAttempt((a) => a + 1)}>
            다시 시도
          </button>
        </>
      ) : !data ? (
        <p>불러오는 중…</p>
      ) : (
        <ul className="cards">
          {PROVIDERS.map((p) => (
            <ProviderRow key={p} provider={p} data={data} onChanged={() => setAttempt((a) => a + 1)} />
          ))}
        </ul>
      )}
    </>
  );
}

function ProviderRow({ provider, data, onChanged }: { provider: ProviderPath; data: MySocial; onChanged: () => void }) {
  const id = useId();
  const toast = useUi((s) => s.toast);
  const label = PROVIDER_LABEL[provider];
  const linked = data.items.find((i) => i.provider.toLowerCase() === provider);
  const enabled = data.providers[provider];
  const [unlinking, setUnlinking] = useState(false);
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function unlink(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api(`/api/me/social/${provider}`, { method: "DELETE", body: { password } });
      toast(`${label} 연결을 해제했어요.`);
      setUnlinking(false);
      setPassword("");
      onChanged();
    } catch (e) {
      setFailure(e instanceof ApiError && e.code === "PASSWORD_MISMATCH" ? "비밀번호가 맞지 않아요." : errorMessage(e));
    }
    setBusy(false);
  }

  return (
    <li className="card" data-testid={`social-${label}`}>
      <strong>{label}</strong>
      {linked ? (
        <>
          <span className="badge confirmed">연결됨</span>
          <span className="meta">{formatSeoul(linked.linkedAt)}에 연결</span>
          {!unlinking ? (
            <button type="button" className="secondary card-button" onClick={() => setUnlinking(true)}>
              {label} 연결 해제
            </button>
          ) : (
            <form className="form" onSubmit={unlink}>
              <div className="field">
                <label htmlFor={`${id}-pw`}>비밀번호 확인</label>
                <input id={`${id}-pw`} type="password" autoComplete="current-password" value={password} required onChange={(e) => setPassword(e.target.value)} />
              </div>
              <div className="actions">
                <button type="submit" className="danger" disabled={busy}>
                  {busy ? "해제 중…" : "해제"}
                </button>
                <button type="button" className="secondary" onClick={() => setUnlinking(false)}>
                  취소
                </button>
              </div>
              {failure && <p role="alert">{failure}</p>}
            </form>
          )}
        </>
      ) : enabled ? (
        <>
          <span className="meta">연결 안 됨</span>
          {/* 공급자 화면으로 이동해야 해서 fetch 가 아닌 링크로 간다(서버가 state 를 세션에 두고 302) */}
          <a href={`/api/auth/oauth/${provider}/link/start`} className="button-link card-button">
            {label} 연결하기
          </a>
        </>
      ) : (
        <span className="badge" aria-label={`${label} 준비 중`}>
          준비 중
        </span>
      )}
    </li>
  );
}
