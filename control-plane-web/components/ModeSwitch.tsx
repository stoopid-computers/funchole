"use client";

import { useMode } from "@/lib/mode";
import { cn } from "@/lib/utils";

// Simple (everyday words, the essentials) vs Advanced (every technical screen).
export function ModeSwitch({ className }: { className?: string }) {
  const { mode, setMode } = useMode();
  return (
    <div role="group" aria-label="How much detail to show" className={cn("inline-flex rounded-full border-2 border-edge bg-card p-0.5", className)}>
      {(["simple", "advanced"] as const).map((value) => (
        <button
          key={value}
          type="button"
          aria-pressed={mode === value}
          onClick={() => setMode(value)}
          className={cn(
            "h-7 cursor-pointer rounded-full px-3 text-xs font-semibold transition-colors",
            mode === value ? "bg-sun text-[var(--fh-on-sun)]" : "text-muted-foreground hover:text-foreground"
          )}
        >
          {value === "simple" ? "Simple" : "Advanced"}
        </button>
      ))}
    </div>
  );
}
