"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { ClubDetail } from "@/components/club/ClubDetail";

export function Detail() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <ClubDetail clubId={id} me={me} />}</AuthGate>;
}
