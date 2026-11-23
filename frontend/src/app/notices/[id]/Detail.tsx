"use client";

import { useParams } from "next/navigation";

import { OptionalAuthGate } from "@/components/AuthGate";
import { PostDetail } from "@/components/board/PostDetail";

export function Detail() {
  const { id } = useParams<{ id: string }>();
  return <OptionalAuthGate>{() => <PostDetail kind="notice" postId={id} />}</OptionalAuthGate>;
}
