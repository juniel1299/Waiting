"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import { CHAT_POLL_MS, mergeMessages, type ChatMessage, type ChatPage } from "@/lib/chat";
import { useUi } from "@/lib/uiStore";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

export function Chat() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <ClubChat clubId={id} />}</AuthGate>;
}

/**
 * 모임 채팅: 처음에 최근 50건, 그 뒤로는 화면이 보이는 동안 몇 초마다 cursor 이후 변경(새 메시지·삭제)을 이어 받는다.
 * 탭으로 돌아오거나 네트워크가 돌아오면 바로 한 번 이어 받아 놓친 메시지가 없다. 회원이 아니게 되면(탈퇴 등) 안내로 바뀐다.
 */
function ClubChat({ clubId }: { clubId: string }) {
  const id = useId();
  const toast = useUi((s) => s.toast);
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [failure, setFailure] = useState<{ text: string; forbidden: boolean } | null>(null);
  const [draft, setDraft] = useState("");
  const [sendError, setSendError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cursor = useRef<number | null>(null);
  const loading = useRef(false);
  const listRef = useRef<HTMLOListElement>(null);

  const pull = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    try {
      let more = true;
      while (more) {
        const after = cursor.current;
        const { data } = await api<ChatPage>(`/api/clubs/${clubId}/chat${after === null ? "" : `?after=${after}`}`);
        cursor.current = data.cursor;
        setMessages((prev) => (prev === null || after === null ? data.items : mergeMessages(prev, data.items)));
        more = data.hasMore;
      }
      setFailure(null);
    } catch (e) {
      const forbidden = e instanceof ApiError && (e.status === 403 || e.status === 404);
      setFailure({ text: forbidden ? "모임 회원만 채팅을 볼 수 있어요." : errorMessage(e), forbidden });
    } finally {
      loading.current = false;
    }
  }, [clubId]);

  useEffect(() => {
    void pull();
  }, [pull]);
  useVisiblePolling({ refresh: () => void (failure?.forbidden ? undefined : pull()) }, CHAT_POLL_MS);

  // 새 메시지가 오면 맨 아래 근처에 있을 때만 따라 내려간다(위로 읽는 중이면 그대로)
  const count = messages?.length ?? 0;
  useEffect(() => {
    const list = listRef.current;
    if (list && list.scrollHeight - list.scrollTop - list.clientHeight < 120) list.scrollTop = list.scrollHeight;
  }, [count]);

  async function send(event?: FormEvent) {
    event?.preventDefault();
    if (!draft.trim() || busy) return;
    setBusy(true);
    setSendError(null);
    try {
      const { data } = await api<ChatMessage>(`/api/clubs/${clubId}/chat`, { method: "POST", body: { body: draft } });
      setMessages((prev) => mergeMessages(prev ?? [], [data]));
      setDraft("");
      const list = listRef.current;
      if (list) requestAnimationFrame(() => (list.scrollTop = list.scrollHeight));
    } catch (e) {
      setSendError(e instanceof ApiError && e.code === "CHAT_TOO_FAST" ? "너무 빨리 보내고 있어요. 잠시 후 다시 보내 주세요." : errorMessage(e));
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
      await api(`/api/clubs/${clubId}/chat/${message.id}`, { method: "DELETE" });
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
      <p className="meta">모임 회원만 볼 수 있어요. 새 메시지는 몇 초마다 이어 받아요. 지운 메시지는 내용 없이 표시만 남아요.</p>
      {failure && <p role="alert">{failure.text} 잠시 뒤 다시 이어 받을게요.</p>}
      {messages === null ? (
        <p>불러오는 중…</p>
      ) : (
        <ol className="chat-log" role="log" aria-live="polite" aria-label="채팅 메시지" ref={listRef}>
          {messages.length === 0 && <li className="meta">아직 메시지가 없어요. 먼저 인사해 보세요.</li>}
          {messages.map((m) => (
            <li key={m.id} className={m.mine ? "chat-item mine" : "chat-item"}>
              <span className="meta">
                {m.author?.label} · {m.createdAt ? formatSeoul(m.createdAt) : ""}
              </span>
              {m.deleted ? <span className="chat-deleted">삭제된 메시지예요</span> : <span className="prewrap chat-body">{m.body}</span>}
              {m.canDelete && (
                <button type="button" className="secondary chat-delete" onClick={() => remove(m)}>
                  삭제
                </button>
              )}
            </li>
          ))}
        </ol>
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
