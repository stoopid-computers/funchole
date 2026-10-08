"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

const ToastContext = createContext<(message: string) => void>(() => {});

// The landing page's toast: a dark pill at the bottom, gone after a moment.
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const show = useCallback((text: string) => {
    setMessage(text);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setMessage(null), 2600);
  }, []);

  useEffect(() => () => clearTimeout(timer.current), []);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <p
        role="status"
        aria-live="polite"
        className={cn(
          "pointer-events-none fixed bottom-6 left-1/2 z-[80] max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-full bg-foreground px-5 py-3 text-center text-sm font-semibold text-background transition-all duration-200",
          message ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
        )}
      >
        {message}
      </p>
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}
