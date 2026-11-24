"use client";

import { AuthGate } from "@/components/AuthGate";
import { ClubForm } from "@/components/club/ClubForm";

export default function NewClubPage() {
  return <AuthGate>{(me) => <ClubForm me={me} />}</AuthGate>;
}
