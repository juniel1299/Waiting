"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { AuthGate } from "@/components/AuthGate";
import { FeedbackView } from "@/components/FeedbackView";
import { api, ApiError, errorMessage } from "@/lib/api";
import type { FeedbackDetail } from "@/lib/feedback";

export function Detail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <MyFeedbackDetail id={id} />}</AuthGate>;
}

/** 내가 보낸 개선 요청 하나: 상태·처리 내용·PR 링크·상태 기록(사유). */
function MyFeedbackDetail({ id }: { id: string }) {
  const [item, setItem] = useState<FeedbackDetail | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    api<FeedbackDetail>(`/api/feedback/${id}`)
      .then((res) => setItem(res.data))
      .catch((e: unknown) => setFailure(e instanceof ApiError && e.status === 404 ? "찾을 수 없는 요청이에요." : errorMessage(e)));
  }, [id]);
  if (failure) {
    return (
      <>
        <p role="alert">{failure}</p>
        <Link href="/me/feedback" className="link">
          개선 요청으로
        </Link>
      </>
    );
  }
  if (!item) return <p>불러오는 중…</p>;
  return (
    <>
      <Link href="/me/feedback" className="link">
        ← 개선 요청
      </Link>
      <FeedbackView item={item} />
    </>
  );
}
