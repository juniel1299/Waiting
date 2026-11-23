"use client";

import { AuthGate } from "@/components/AuthGate";
import { PostForm } from "@/components/board/PostForm";

export default function NewPostPage() {
  return <AuthGate>{(me) => <PostForm kind="notice" me={me} />}</AuthGate>;
}
