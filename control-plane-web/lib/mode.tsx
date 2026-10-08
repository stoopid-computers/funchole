"use client";

import { createContext, useCallback, useContext, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { capitalize, noun, term, type Mode, type NounKey, type TermKey } from "@/lib/copy";
import { PAGE_COPY, type PageKey } from "@/lib/pagecopy";

// Simple (the default) hides engineering terms and technical screens;
// Advanced keeps everything. The choice is stored per browser.
const KEY = "fh_mode";

// Advanced is parked: everyone sees Simple and nothing offers a way into
// Advanced. Flip this to true to bring the switch and the technical screens back.
export const ADVANCED_ENABLED = false;
const listeners = new Set<() => void>();

function read(): Mode | null {
  try {
    const saved = localStorage.getItem(KEY);
    return saved === "simple" || saved === "advanced" ? saved : null;
  } catch {
    return null;
  }
}

export function saveMode(mode: Mode) {
  if (mode === "advanced" && !ADVANCED_ENABLED) return;
  try {
    localStorage.setItem(KEY, mode);
  } catch {
    // Private mode: the choice just won't persist.
  }
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener("storage", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", listener);
  };
}

// Everyone starts in Simple. People who already have functions or flows (the
// first time we see them in the new workspace) are moved to Advanced once, so
// existing builders are never surprised. `null` means "no choice made yet".
export function useSavedMode() {
  return useSyncExternalStore<Mode | null>(subscribe, read, () => null);
}

interface ModeContextValue {
  mode: Mode;
  setMode: (mode: Mode) => void;
  /** Title-case label for a term (menus, buttons, headings). */
  label: (key: TermKey) => string;
  /** Lower-case noun for sentences; pass true for the plural. */
  noun: (key: NounKey, plural?: boolean) => string;
  /** Capitalised noun, e.g. "Workflows" / "Pages & APIs". */
  title: (key: NounKey, plural?: boolean) => string;
}

const ModeContext = createContext<ModeContextValue>({
  mode: "simple",
  setMode: saveMode,
  label: (key) => term(key, "simple"),
  noun: (key, plural) => noun(key, "simple", plural),
  title: (key, plural) => capitalize(noun(key, "simple", plural)),
});

export function ModeProvider({ children }: { children: ReactNode }) {
  const saved = useSavedMode();
  const mode: Mode = ADVANCED_ENABLED && saved === "advanced" ? "advanced" : "simple";
  const setMode = useCallback((next: Mode) => saveMode(next), []);
  const value = useMemo<ModeContextValue>(
    () => ({
      mode,
      setMode,
      label: (key) => term(key, mode),
      noun: (key, plural) => noun(key, mode, plural),
      title: (key, plural) => capitalize(noun(key, mode, plural)),
    }),
    [mode, setMode]
  );
  return <ModeContext.Provider value={value}>{children}</ModeContext.Provider>;
}

export function useMode() {
  return useContext(ModeContext);
}

// Headings, list titles and empty states for a page, in the current mode.
export function usePageCopy(key: PageKey) {
  const { mode } = useMode();
  return PAGE_COPY[key][mode];
}
