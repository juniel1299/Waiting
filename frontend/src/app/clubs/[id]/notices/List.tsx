"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { BoardList } from "@/components/board/BoardList";

export function List() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <BoardList kind="club-notice" me={me} clubId={id} />}</AuthGate>;
}
