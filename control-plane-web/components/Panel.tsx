import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

// The same surface as shadcn's Card (and the landing page's `.panel`), without
// Card's built-in padding/gap so existing layouts keep control of spacing.
export const panelClass = "rounded-xl border border-border bg-card text-card-foreground";

export function Panel({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="card" className={cn(panelClass, className)} {...props} />;
}
