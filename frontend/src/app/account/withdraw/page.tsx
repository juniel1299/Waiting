"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, type Me, type Reservation } from "@/lib/api";

export default function WithdrawPage() {
  return <AuthGate>{(me) => <Withdraw me={me} />}</AuthGate>;
}

interface Summary {
  upcomingConfirmed: number;
  waiting: number;
  startedConfirmed: number;
}

function summarize(reservations: Reservation[], now: number): Summary {
  const upcoming = (r: Reservation) => new Date(r.startsAt).getTime() > now;
  return {
    upcomingConfirmed: reservations.filter((r) => r.status === "CONFIRMED" && upcoming(r)).length,
    waiting: reservations.filter((r) => r.status === "WAITING").length,
    startedConfirmed: reservations.filter((r) => r.status === "CONFIRMED" && !upcoming(r)).length,
  };
}

function count(n: number | undefined): string {
  return n === undefined ? "" : `(지금 ${n}건)`;
}

/** 탈퇴 확인 화면: 계정·신청 정보 처리 방식을 설명하고 비밀번호를 다시 받는다. 처리 규칙은 서버(WithdrawalService)가 정한다. */
function Withdraw({ me }: { me: Me }) {
  const router = useRouter();
  const [summary, setSummary] = useState<Summary | null>(null);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const allowed = me.role === "PARTICIPANT" && !me.demo;
  // cacheComponents 는 떠난 화면을 숨긴 채 상태를 남긴다: 숨겨질 때 진행 중 표시와 비밀번호를 비운다
  useEffect(
    () => () => {
      setBusy(false);
      setPassword("");
    },
    [],
  );

  useEffect(() => {
    if (!allowed) return;
    api<Reservation[]>("/api/me/reservations")
      .then(({ data }) => setSummary(summarize(data, Date.now())))
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [allowed]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await api("/api/me/withdraw", { method: "POST", body: { password } });
      router.replace("/login?withdrawn=1");
    } catch (e) {
      setBusy(false);
      if (e instanceof ApiError && e.code === "PASSWORD_MISMATCH") {
        setError("비밀번호가 올바르지 않아요. 다시 입력해 주세요.");
      } else if (e instanceof ApiError && e.code === "TOO_MANY_ATTEMPTS") {
        setError(errorMessage(e).replace("로그인 시도", "비밀번호 확인 시도"));
      } else {
        setError(errorMessage(e));
      }
    }
  }

  if (!allowed) {
    return (
      <>
        <h1>회원 탈퇴</h1>
        <p role="alert">운영자·체험 계정은 탈퇴할 수 없어요.</p>
      </>
    );
  }
  return (
    <>
      <h1>회원 탈퇴</h1>
      <section className="card" aria-labelledby="withdraw-effects">
        <h2 id="withdraw-effects">탈퇴하면 이렇게 처리돼요</h2>
        <ul className="effects">
          <li>
            아이디 <strong>{me.loginId}</strong> 와 이름은 지워지고 &lsquo;탈퇴한 회원&rsquo;으로 바뀌어요. 같은 아이디로
            다시 가입할 수 있지만 지금 기록과는 연결되지 않아요.
          </li>
          <li>
            시작 전 확정 예약{count(summary?.upcomingConfirmed)}은 취소되고, 대기 중인 다음 분이 자동으로 확정돼요.
          </li>
          <li>대기 신청{count(summary?.waiting)}은 모두 취소돼요.</li>
          <li>
            이미 시작된 회차의 참가 기록{count(summary?.startedConfirmed)}은 운영 기록으로 남고 &lsquo;탈퇴한
            회원&rsquo;으로 표시돼요.
          </li>
          <li>다른 기기에서 로그인한 상태도 모두 끊겨요. 탈퇴는 되돌릴 수 없어요.</li>
        </ul>
      </section>
      <form onSubmit={submit} className="form">
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
        <button type="submit" className={busy ? "danger loading" : "danger"} aria-busy={busy} aria-disabled={busy}>
          {busy ? "탈퇴하는 중…" : "탈퇴하기"}
        </button>
        {error && <p role="alert">{error}</p>}
      </form>
    </>
  );
}
