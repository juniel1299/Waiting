"use client";

import { useParams } from "next/navigation";

import { AuthGate } from "@/components/AuthGate";
import { ClubForm } from "@/components/club/ClubForm";

export function Edit() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{(me) => <ClubForm me={me} clubId={id} />}</AuthGate>;
}
