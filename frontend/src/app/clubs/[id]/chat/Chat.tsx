"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import {
  anchorScrollTop,
  appendedCount,
  CHAT_POLL_MS,
  isNearBottom,
  mergeMessages,
  type ChatHistory,
  type ChatMessage,
  type ChatPage,
} from "@/lib/chat";
import { useUi } from "@/lib/uiStore";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export function Chat() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <ClubChat clubId={id} />}</AuthGate>;
}

/**
 * 모임 채팅: 처음에 최근 50건, 그 뒤로는 화면이 보이는 동안 몇 초마다 cursor 이후 변경(새 메시지·삭제)을 이어 받는다.
 * 탭으로 돌아오거나 네트워크가 돌아오면 바로 한 번 이어 받아 놓친 메시지가 없다. 회원이 아니게 되면(탈퇴 등) 안내로 바뀐다.
 * 위의 '이전 메시지 더 보기'는 가장 오래된 메시지 id 보다 앞을 불러와 붙이고, 보던 메시지가 제자리에 남게 스크롤을 맞춘다.
 * 이전 기록은 이어 받기 cursor 를 바꾸지 않는다(읽음 위치가 뒤로 가지 않음). 모든 합치기는 id 기준이라 중복·누락이 없다.
 * 맨 아래 근처면 새 메시지를 따라가고, 위를 보고 있으면 스크롤은 그대로 두고 '새 메시지 N개' 안내를 띄운다.
 */
function ClubChat({ clubId }: { clubId: string }) {
  const id = useId();
  const toast = useUi((s) => s.toast);
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [failure, setFailure] = useState<{
    text: string;
    forbidden: boolean;
  } | null>(null);
  const [draft, setDraft] = useState("");
  const [sendError, setSendError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [older, setOlder] = useState<"idle" | "loading" | "error" | "end">(
    "idle",
  );
  const [unseen, setUnseen] = useState(0);
  const cursor = useRef<number | null>(null);
  const loading = useRef(false);
  const listRef = useRef<HTMLOListElement>(null);
  /** 합치기의 기준이 되는 지금 목록(이어 받기·이전 기록·보내기가 같은 것을 본다) */
  const current = useRef<ChatMessage[] | null>(null);
  /** 이전 기록을 붙이기 직전 스크롤 상태: 다음 그리기에서 보던 위치로 되돌린다 */
  const anchor = useRef<{ scrollHeight: number; scrollTop: number } | null>(
    null,
  );
  /** 맨 아래 근처에 있는지(사용자가 스크롤할 때마다 갱신) */
  const stick = useRef(true);

  const show = useCallback((next: ChatMessage[]) => {
    const prev = current.current;
    current.current = next;
    setMessages(next);
    if (prev && !stick.current) {
      const added = appendedCount(prev, next);
      if (added > 0) setUnseen((n) => n + added);
    }
  }, []);

  const pull = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    try {
      let more = true;
      while (more) {
        const after = cursor.current;
        const { data } = await api<ChatPage>(
          `/api/clubs/${clubId}/chat${after === null ? "" : `?after=${after}`}`,
        );
        cursor.current = data.cursor;
        if (after === null) {
          current.current = null;
          setOlder(data.hasMoreBefore ? "idle" : "end");
          show(data.items);
        } else {
          show(mergeMessages(current.current ?? [], data.items));
        }
        more = data.hasMore;
      }
      setFailure(null);
    } catch (e) {
      const forbidden =
        e instanceof ApiError && (e.status === 403 || e.status === 404);
      setFailure({
        text: forbidden ? "모임 회원만 채팅을 볼 수 있어요." : errorMessage(e),
        forbidden,
      });
    } finally {
      loading.current = false;
    }
  }, [clubId, show]);

  useEffect(() => {
    // pull 은 응답을 받은 뒤(await 다음)에만 상태를 바꾼다
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void pull();
  }, [pull]);
  useVisiblePolling(
    { refresh: () => void (failure?.forbidden ? undefined : pull()) },
    CHAT_POLL_MS,
  );

  // 그린 뒤 스크롤: 이전 기록을 붙였으면 보던 자리로, 아니면 맨 아래 근처일 때만 따라 내려간다
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list || !messages) return;
    if (anchor.current) {
      list.scrollTop = anchorScrollTop(anchor.current, list.scrollHeight);
      anchor.current = null;
    } else if (stick.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages]);

  function onScroll() {
    const list = listRef.current;
    if (!list) return;
    stick.current = isNearBottom(list);
    if (stick.current) setUnseen(0);
  }

  function toBottom() {
    const list = listRef.current;
    stick.current = true;
    setUnseen(0);
    if (list) list.scrollTop = list.scrollHeight;
  }

  async function loadOlder() {
    const list = listRef.current;
    const first = current.current?.[0];
    if (!list || !first || older === "loading") return;
    setOlder("loading");
    try {
      const { data } = await api<ChatHistory>(
        `/api/clubs/${clubId}/chat?before=${first.id}&limit=50`,
      );
      anchor.current = {
        scrollHeight: list.scrollHeight,
        scrollTop: list.scrollTop,
      };
      show(mergeMessages(current.current ?? [], data.items));
      setOlder(data.hasMore ? "idle" : "end");
    } catch (e) {
      if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
        setFailure({
          text: "모임 회원만 채팅을 볼 수 있어요.",
          forbidden: true,
        });
      } else {
        setOlder("error");
      }
    }
  }

  async function send(event?: FormEvent) {
    event?.preventDefault();
    if (!draft.trim() || busy) return;
    setBusy(true);
    setSendError(null);
    try {
      const { data } = await api<ChatMessage>(`/api/clubs/${clubId}/chat`, {
        method: "POST",
        body: { body: draft },
      });
      stick.current = true;
      setUnseen(0);
      show(mergeMessages(current.current ?? [], [data]));
      setDraft("");
    } catch (e) {
      setSendError(
        e instanceof ApiError && e.code === "CHAT_TOO_FAST"
          ? "너무 빨리 보내고 있어요. 잠시 후 다시 보내 주세요."
          : errorMessage(e),
      );
    }
    setBusy(false);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter 는 보내기, Shift+Enter 는 줄바꿈. 한글 조합 중(isComposing)에는 보내지 않는다
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void send();
    }
  }

  async function remove(message: ChatMessage) {
    try {
      await api(`/api/clubs/${clubId}/chat/${message.id}`, {
        method: "DELETE",
      });
      await pull();
    } catch (e) {
      toast(errorMessage(e), "error");
    }
  }

  if (failure?.forbidden) {
    return (
      <>
        <p role="alert">{failure.text}</p>
        <Link href={`/clubs/${clubId}`} className="link">
          모임 소개로
        </Link>
      </>
    );
  }
  return (
    <>
      <Link href={`/clubs/${clubId}`} className="link">
        ← 모임 소개
      </Link>
      <h1>모임 채팅</h1>
      <p className="meta">
        모임 회원만 볼 수 있어요. 새 메시지는 몇 초마다 이어 받아요. 지운
        메시지는 내용 없이 표시만 남아요.
      </p>
      {failure && (
        <p role="alert">{failure.text} 잠시 뒤 다시 이어 받을게요.</p>
      )}
      {messages === null ? (
        <p>불러오는 중…</p>
      ) : (
        <>
          {messages.length > 0 && (
            <div className="chat-older">
              {older === "idle" && (
                <button type="button" className="secondary" onClick={loadOlder}>
                  이전 메시지 더 보기
                </button>
              )}
              {older === "loading" && (
                <button type="button" className="secondary" disabled>
                  불러오는 중…
                </button>
              )}
              {older === "error" && (
                <>
                  <p role="alert">이전 메시지를 불러오지 못했어요.</p>
                  <button
                    type="button"
                    className="secondary"
                    onClick={loadOlder}
                  >
                    다시 시도
                  </button>
                </>
              )}
              {older === "end" && <p className="meta">처음 메시지예요.</p>}
            </div>
          )}
          <ol
            className="chat-log"
            role="log"
            aria-live="polite"
            aria-label="채팅 메시지"
            ref={listRef}
            onScroll={onScroll}
          >
            {messages.length === 0 && (
              <li className="meta">
                아직 메시지가 없어요. 먼저 인사해 보세요.
              </li>
            )}
            {messages.map((m) => (
              <li
                key={m.id}
                className={m.mine ? "chat-item mine" : "chat-item"}
              >
                <span className="meta">
                  {m.author?.label} ·{" "}
                  {m.createdAt ? formatSeoul(m.createdAt) : ""}
                </span>
                {m.deleted ? (
                  <span className="chat-deleted">삭제된 메시지예요</span>
                ) : (
                  <span className="prewrap chat-body">{m.body}</span>
                )}
                {m.canDelete && (
                  <button
                    type="button"
                    className="secondary chat-delete"
                    onClick={() => remove(m)}
                  >
                    삭제
                  </button>
                )}
              </li>
            ))}
          </ol>
          {unseen > 0 && (
            <button type="button" className="chat-new" onClick={toBottom}>
              새 메시지 {unseen}개 ↓
            </button>
          )}
        </>
      )}
      <form className="chat-form" onSubmit={send}>
        <label htmlFor={`${id}-msg`}>메시지</label>
        <textarea
          id={`${id}-msg`}
          value={draft}
          maxLength={1000}
          rows={2}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          aria-describedby={`${id}-help`}
        />
        <span id={`${id}-help`} className="meta">
          Enter 로 보내기, Shift+Enter 로 줄바꿈 · {draft.length} / 1000
        </span>
        <button type="submit" disabled={busy || !draft.trim()}>
          {busy ? "보내는 중…" : "보내기"}
        </button>
        {sendError && <p role="alert">{sendError}</p>}
      </form>
    </>
  );
}
