"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { PostForm } from "@/components/board/PostForm";

export function New() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <PostForm kind="club-board" me={me} clubId={id} />}</AuthGate>;
}
