"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { PostDetail } from "@/components/board/PostDetail";

export function Detail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <PostDetail kind="community" postId={id} me={me} />}</AuthGate>;
}
