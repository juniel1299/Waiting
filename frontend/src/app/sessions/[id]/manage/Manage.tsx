"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, errorMessage, formatSeoul, STATUS_LABEL, type RosterEntry, type StudySession } from "@/lib/api";

/** 회차 관리: 생성자는 명단·정원·삭제, 운영자는 명단·삭제. 권한은 서버가 정하고(다른 회원은 403) 화면은 응답을 따른다. */
export function Manage() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <RosterTable sessionId={id} />}</AuthGate>;
}

interface RosterData {
  entries: RosterEntry[];
  session: StudySession;
}

interface CapacityChangeResult {
  session: StudySession;
  promotedCount: number;
}

/** 역할로 화면을 숨기지 않고 서버 응답(참가자는 403)을 그대로 따른다. */
function RosterTable({ sessionId }: { sessionId: string }) {
  const [data, setData] = useState<RosterData | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchRoster = useCallback(async (): Promise<RosterData> => {
    const [roster, session] = await Promise.all([
      api<RosterEntry[]>(`/api/sessions/${sessionId}/roster`),
      api<StudySession>(`/api/sessions/${sessionId}`),
    ]);
    return { entries: roster.data, session: session.data };
  }, [sessionId]);

  useEffect(() => {
    fetchRoster()
      .then(setData)
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [fetchRoster]);

  const entries = data?.entries ?? null;

  return (
    <>
      <Link href={`/sessions/${sessionId}`} className="link">
        ← 회차 상세
      </Link>
      {error && <p role="alert">{error}</p>}
      {!error && entries === null && <p>불러오는 중…</p>}
      {entries && data && (
        <>
          <h1 className="title-text">회차 관리 · {data.session.title}</h1>
          {/* 1) 회차 정보 2) 인원 3) 정원 변경 4) 명단 5) 관리 작업(삭제는 따로) */}
          <section className="panel" aria-labelledby="manage-info">
            <h2 id="manage-info">회차 정보</h2>
            <dl className="facts">
              <dt>일시</dt>
              <dd>{formatSeoul(data.session.startsAt)}</dd>
              <dt>장소</dt>
              <dd className="wrap-anywhere">{data.session.location}</dd>
              <dt>모집 상태</dt>
              <dd>
                <span className={`badge status-${data.session.status.toLowerCase()}`}>{STATUS_LABEL[data.session.status]}</span>
              </dd>
            </dl>
            <ul className="stat-cards compact" aria-label="인원">
              <li className="stat-card">
                <span className="meta">정원</span>
                <strong className="stat-value">{data.session.capacity}명</strong>
              </li>
              <li className="stat-card">
                <span className="meta">확정</span>
                <strong className="stat-value">{data.session.confirmedCount}명</strong>
              </li>
              <li className="stat-card">
                <span className="meta">대기</span>
                <strong className="stat-value">{data.session.waitingCount}명</strong>
              </li>
            </ul>
          </section>
          {data.session.canManage && (
            <CapacityForm session={data.session} onChanged={() => fetchRoster().then(setData)} />
          )}
          <section className="panel" aria-labelledby="manage-roster">
            <h2 id="manage-roster">확정 명단</h2>
            {entries.length === 0 ? (
              <p className="meta">확정된 참가자가 없어요.</p>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">이름</th>
                      <th scope="col">아이디</th>
                      <th scope="col">예약 시각</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((e) => (
                      <tr key={e.reservationId}>
                        <td>{e.displayName}</td>
                        <td>{e.loginId}</td>
                        <td>{formatSeoul(e.reservedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="meta">
              대기 {data.session.waitingCount}명은 신청 순서대로 자리가 나면 자동으로 확정돼요.
            </p>
          </section>
          {(data.session.canManage || data.session.canDelete) && (
            <section className="panel" aria-labelledby="manage-actions">
              <h2 id="manage-actions">관리 작업</h2>
              {data.session.canManage && data.session.status !== "STARTED" && (
                <div className="actions">
                  <Link href={`/sessions/${sessionId}/edit`} className="button-link secondary-link">
                    제목·시작·장소 수정
                  </Link>
                </div>
              )}
              {data.session.canDelete && <DeleteSession session={data.session} />}
            </section>
          )}
        </>
      )}
    </>
  );
}

function CapacityForm({ session, onChanged }: { session: StudySession; onChanged: () => Promise<void> }) {
  const [capacity, setCapacity] = useState("");
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const { data } = await api<CapacityChangeResult>(`/api/sessions/${session.id}/capacity`, {
        method: "PATCH",
        body: { capacity: Number(capacity) },
      });
      const promoted = data.promotedCount > 0 ? ` 대기자 ${data.promotedCount}명이 확정됐어요.` : "";
      setNotice({ kind: "status", text: `정원을 ${data.session.capacity}명으로 바꿨어요.${promoted}` });
      setCapacity("");
      await onChanged();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    setBusy(false);
  }

  return (
    <section className="panel" aria-labelledby="manage-capacity">
      <h2 id="manage-capacity">정원 변경</h2>
      <p data-testid="capacity" className="meta">
        정원 {session.capacity}명 · 확정 {session.confirmedCount}명 · 대기 {session.waitingCount}명
      </p>
      <form onSubmit={submit} className="search-row capacity-row">
        <div className="field">
          <label htmlFor={`capacity-${session.id}`}>새 정원</label>
          <input
            id={`capacity-${session.id}`}
            type="number"
            inputMode="numeric"
            min={1}
            max={1000}
            step={1}
            value={capacity}
            onChange={(e) => setCapacity(e.target.value)}
            required
          />
        </div>
        <div className="field end">
          <button type="submit" disabled={busy || session.status === "STARTED"}>
            정원 변경
          </button>
        </div>
      </form>
      {session.status === "STARTED" && <p className="meta">시작된 회차는 정원을 바꿀 수 없어요.</p>}
      {notice && <p role={notice.kind}>{notice.text}</p>}
    </section>
  );
}

/** 물리 삭제. 연결된 신청(확정·대기·취소)도 함께 지워지고 복구할 수 없다고 확인받는다. */
function DeleteSession({ session }: { session: StudySession }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    const message =
      `'${session.title}' 회차를 삭제할까요?\n` +
      `확정 ${session.confirmedCount}명·대기 ${session.waitingCount}명을 포함한 이 회차의 신청이 모두 함께 삭제되고, ` +
      "대기자 승격 없이 끝나며 복구할 수 없어요.";
    if (!window.confirm(message)) return;
    setBusy(true);
    try {
      await api(`/api/sessions/${session.id}`, { method: "DELETE" });
      router.replace(session.canManage ? "/me/sessions" : "/sessions");
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <div className="danger-zone" aria-label="회차 삭제" role="group">
      <p className="meta">삭제하면 이 회차의 신청이 모두 함께 지워지고 복구할 수 없어요.</p>
      <button type="button" className="danger" onClick={remove} disabled={busy}>
        회차 삭제
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}
