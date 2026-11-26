"use client";

import { useCallback, useEffect, useId, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { OperatorHead } from "@/components/OperatorTabs";
import { api, errorMessage, formatSeoul } from "@/lib/api";
import { useUi } from "@/lib/uiStore";

type ServiceRole = "OPERATOR" | "DEVELOPER";
interface AccountRoles {
  accountId: number;
  loginId: string;
  label: string;
  roles: ServiceRole[];
}
interface Change {
  id: number;
  target: string;
  role: ServiceRole;
  action: "GRANT" | "REVOKE";
  reason: string;
  actor: string;
  createdAt: string;
}

const ROLE_LABEL: Record<ServiceRole, string> = { OPERATOR: "운영자", DEVELOPER: "개발자" };

/** 역할별 기능 권한표 (판단 근거는 서버, 이 표는 안내). */
const MATRIX: [string, string, string, string][] = [
  ["게시판·모임·회차 이용", "○", "기존 정책", "회원과 같음"],
  ["계정 정지·해제·강제탈퇴, 모임 승인, 대시보드", "✗", "○", "✗"],
  ["서비스 권한 관리 (자기 자신 제외)", "✗", "○", "✗"],
  ["개선사항·에러 로그 (3단계)", "✗", "✗", "○"],
  ["비공개 콘텐츠(모임 내부) 전체 열람", "✗", "조회·삭제만", "✗"],
];

export default function RolesPage() {
  return <AuthGate>{(me) => (me.role === "OPERATOR" ? <Roles myId={me.id} /> : <p role="alert">운영자만 볼 수 있어요.</p>)}</AuthGate>;
}

/** 서비스 권한 관리: 권한표, 계정별 역할 조회·부여·회수(사유 필수), 변경 기록. */
function Roles({ myId }: { myId: number }) {
  const id = useId();
  const toast = useUi((s) => s.toast);
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<AccountRoles[] | null>(null);
  const [changes, setChanges] = useState<Change[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    const ctrl = new AbortController();
    Promise.all([
      api<{ items: AccountRoles[] }>(`/api/operator/roles?size=50${query ? `&q=${encodeURIComponent(query)}` : ""}`, { signal: ctrl.signal }),
      api<{ items: Change[] }>("/api/operator/roles/changes", { signal: ctrl.signal }),
    ])
      .then(([r, c]) => {
        setItems(r.data.items);
        setChanges(c.data.items);
        setError(null);
      })
      .catch((e: unknown) => !ctrl.signal.aborted && setError(errorMessage(e)));
    return ctrl;
  }, [query]);
  useEffect(() => {
    const ctrl = load();
    return () => ctrl.abort();
  }, [load]);

  async function change(a: AccountRoles, role: ServiceRole, grant: boolean) {
    const verb = grant ? "부여" : "회수";
    const reason = window.prompt(`${a.label} 님의 ${ROLE_LABEL[role]} 역할을 ${verb}할까요? 사유를 적어 주세요 (200자 이내).`);
    if (reason === null) return;
    setBusy(true);
    try {
      await api(`/api/operator/roles/${a.accountId}/${role}`, { method: grant ? "PUT" : "DELETE", body: { reason } });
      toast(`${a.label} 님의 ${ROLE_LABEL[role]} 역할을 ${verb}했어요.`);
    } catch (e) {
      toast(errorMessage(e), "error");
    }
    setBusy(false);
    load();
  }

  function search(e: FormEvent) {
    e.preventDefault();
    setQuery(q.trim());
  }

  return (
    <>
      <OperatorHead title="서비스 권한 관리" />
      <h2>역할별 기능</h2>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th scope="col">기능</th>
              <th scope="col">회원</th>
              <th scope="col">운영자</th>
              <th scope="col">개발자</th>
            </tr>
          </thead>
          <tbody>
            {MATRIX.map(([what, ...cells]) => (
              <tr key={what}>
                <th scope="row">{what}</th>
                {cells.map((c, i) => (
                  <td key={i}>{c}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="meta">한 계정에 운영자와 개발자를 함께 줄 수 있어요. 자기 자신의 역할은 바꿀 수 없고, 마지막 운영자는 회수할 수 없어요.</p>

      <h2>계정별 역할</h2>
      <form role="search" aria-label="계정 검색" className="search-form" onSubmit={search}>
        <div className="field grow">
          <label htmlFor={`${id}-q`}>아이디·닉네임</label>
          <input id={`${id}-q`} type="search" value={q} maxLength={100} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="actions">
          <button type="submit">검색</button>
        </div>
      </form>
      {error && <p role="alert">{error}</p>}
      {!items && !error && <p>불러오는 중…</p>}
      {items?.length === 0 && <p className="empty">맞는 계정이 없어요.</p>}
      {items && items.length > 0 && (
        <ul className="member-list">
          {items.map((a) => (
            <li key={a.accountId} className="member-row" data-testid="role-row">
              <strong>{a.label}</strong>
              <span className="meta">{a.loginId}</span>
              {a.roles.length === 0 ? <span className="badge">회원</span> : a.roles.map((r) => <span key={r} className="badge confirmed">{ROLE_LABEL[r]}</span>)}
              {a.accountId === myId ? (
                <span className="meta">나 (바꿀 수 없음)</span>
              ) : (
                <span className="member-actions">
                  {(["OPERATOR", "DEVELOPER"] as ServiceRole[]).map((r) =>
                    a.roles.includes(r) ? (
                      <button key={r} type="button" className="secondary" disabled={busy} onClick={() => change(a, r, false)}>
                        {ROLE_LABEL[r]} 회수
                      </button>
                    ) : (
                      <button key={r} type="button" className="secondary" disabled={busy} onClick={() => change(a, r, true)}>
                        {ROLE_LABEL[r]} 부여
                      </button>
                    ),
                  )}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      <h2>변경 기록</h2>
      {changes.length === 0 ? (
        <p className="empty">아직 변경 기록이 없어요.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">대상</th>
                <th scope="col">역할</th>
                <th scope="col">동작</th>
                <th scope="col">사유</th>
                <th scope="col">처리자</th>
              </tr>
            </thead>
            <tbody>
              {changes.map((c) => (
                <tr key={c.id} data-testid="role-change">
                  <td>{formatSeoul(c.createdAt)}</td>
                  <td>{c.target}</td>
                  <td>{ROLE_LABEL[c.role]}</td>
                  <td>{c.action === "GRANT" ? "부여" : "회수"}</td>
                  <td>{c.reason}</td>
                  <td>{c.actor}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
