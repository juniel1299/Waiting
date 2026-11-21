"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";

import { api, ApiError, errorMessage, formatWait } from "@/lib/api";

type Field = "loginId" | "password" | "passwordConfirm";
type FieldErrors = Partial<Record<Field, string>>;

// 서버(SignupService)와 같은 규칙. 최종 판단은 서버가 한다.
const LOGIN_ID_PATTERN = /^[a-z0-9_]{4,20}$/;
const PASSWORD_MIN_LENGTH = 8;
const PASSWORD_MAX_BYTES = 72;
const FIELDS: Field[] = ["loginId", "password", "passwordConfirm"];

function validate(loginId: string, password: string, passwordConfirm: string): FieldErrors {
  const errors: FieldErrors = {};
  if (!LOGIN_ID_PATTERN.test(loginId)) errors.loginId = "아이디는 영문 소문자·숫자·밑줄(_)로 4~20자여야 해요.";
  if (password.trim().length === 0 || password.length < PASSWORD_MIN_LENGTH) {
    errors.password = "비밀번호는 8자 이상이어야 해요.";
  } else if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    errors.password = "비밀번호가 너무 길어요. 영문 기준 72자 이하로 입력해 주세요.";
  }
  if (passwordConfirm !== password) errors.passwordConfirm = "비밀번호 확인이 일치하지 않아요.";
  return errors;
}

/** 일반 사용자 가입. 역할은 서버가 참가자로 정한다. 성공하면 로그인 화면으로 보낸다. */
export default function SignupPage() {
  const router = useRouter();
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inputs = useRef<Partial<Record<Field, HTMLInputElement | null>>>({});
  // cacheComponents 는 떠난 화면을 숨긴 채 상태를 남긴다: 숨겨질 때 진행 중 표시와 비밀번호를 비운다
  useEffect(
    () => () => {
      setBusy(false);
      setPassword("");
      setPasswordConfirm("");
    },
    [],
  );

  function showFieldErrors(errors: FieldErrors) {
    setFieldErrors(errors);
    const first = FIELDS.find((field) => errors[field]);
    if (first) inputs.current[first]?.focus();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const normalized = loginId.trim().toLowerCase();
    setError(null);
    const errors = validate(normalized, password, passwordConfirm);
    if (Object.keys(errors).length > 0) {
      showFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    setBusy(true);
    try {
      await api("/api/auth/signup", { method: "POST", body: { loginId: normalized, password, passwordConfirm } });
      router.replace(`/login?signedUp=${encodeURIComponent(normalized)}`);
    } catch (e) {
      setBusy(false);
      if (e instanceof ApiError && e.code === "LOGIN_ID_TAKEN") {
        showFieldErrors({ loginId: "이미 사용 중인 아이디예요. 다른 아이디를 입력해 주세요." });
      } else if (e instanceof ApiError && e.fields) {
        showFieldErrors(e.fields as FieldErrors);
      } else if (e instanceof ApiError && e.code === "TOO_MANY_ATTEMPTS") {
        const wait = e.retryAfterSeconds ? `${formatWait(e.retryAfterSeconds)} 후` : "잠시 후";
        setError(`가입 시도가 너무 많아요. ${wait} 다시 시도해 주세요.`);
      } else {
        setError(errorMessage(e));
      }
    }
  }

  function input(field: Field, label: string, value: string, onChange: (v: string) => void, extra: object) {
    const errorId = `${field}-error`;
    // 안내는 label 밖에 둔다: 안에 두면 오류 문구가 칸 이름에 섞인다 (aria-describedby 로만 연결)
    return (
      <div className="field">
        <label>
          {label}
            <input
            ref={(el) => {
              inputs.current[field] = el;
            }}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            aria-invalid={fieldErrors[field] ? true : undefined}
            aria-describedby={fieldErrors[field] ? errorId : undefined}
            required
            {...extra}
          />
        </label>
        {fieldErrors[field] && (
          <span id={errorId} className="field-error">
            {fieldErrors[field]}
          </span>
        )}
      </div>
    );
  }

  return (
    <main className="container narrow">
      <h1>회원가입</h1>
      <form onSubmit={submit} className="form" noValidate>
        {input("loginId", "아이디", loginId, setLoginId, { autoComplete: "username", autoCapitalize: "none" })}
        <p className="meta">영문 소문자·숫자·밑줄(_) 4~20자. 대문자는 소문자로 저장돼요.</p>
        {input("password", "비밀번호", password, setPassword, { type: "password", autoComplete: "new-password" })}
        {input("passwordConfirm", "비밀번호 확인", passwordConfirm, setPasswordConfirm, {
          type: "password",
          autoComplete: "new-password",
        })}
        <button type="submit" aria-busy={busy} aria-disabled={busy} className={busy ? "loading" : undefined}>
          {busy ? "가입하는 중…" : "가입하기"}
        </button>
        {error && <p role="alert">{error}</p>}
      </form>
      <p className="meta">
        이미 계정이 있나요?{" "}
        <Link href="/login" className="link">
          로그인
        </Link>
      </p>
    </main>
  );
}
