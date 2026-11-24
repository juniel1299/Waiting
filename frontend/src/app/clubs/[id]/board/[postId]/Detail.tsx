"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { PostDetail } from "@/components/board/PostDetail";

export function Detail() {
  const { id, postId } = useParams<{ id: string; postId: string }>();
  return <AuthGate>{(me) => <PostDetail kind="club-board" postId={postId} me={me} clubId={id} />}</AuthGate>;
}
