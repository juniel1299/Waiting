"use client";

import { AuthGate } from "@/components/AuthGate";
import { ClubPromo } from "@/components/club/ClubList";

export default function ClubPromoPage() {
  return <AuthGate>{(me) => <ClubPromo me={me} />}</AuthGate>;
}
