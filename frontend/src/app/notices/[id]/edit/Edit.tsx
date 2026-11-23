"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { PostForm } from "@/components/board/PostForm";

export function Edit() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <PostForm kind="notice" me={me} postId={id} />}</AuthGate>;
}
