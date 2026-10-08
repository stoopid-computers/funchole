"use client";

import { createContext, useContext } from "react";
import type { ProfileResponse } from "@/lib/types";

// The dashboard layout loads the profile once and shares it, so pages don't
// each fetch it again.
export const ProfileContext = createContext<{ profile: ProfileResponse | null; refresh: () => void }>({
  profile: null,
  refresh: () => {},
});

export function useProfile() {
  return useContext(ProfileContext);
}
