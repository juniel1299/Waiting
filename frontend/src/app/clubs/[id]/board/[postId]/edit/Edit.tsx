"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { PostForm } from "@/components/board/PostForm";

export function Edit() {
  const { id, postId } = useParams<{ id: string; postId: string }>();
  return <AuthGate>{(me) => <PostForm kind="club-board" me={me} postId={postId} clubId={id} />}</AuthGate>;
}
