import type { ReactNode } from "react";
import { panelClass } from "@/components/Panel";
import { cn } from "@/lib/utils";

interface CreatePanelProps {
  title: string;
  description: string;
  children: ReactNode;
}

export function CreatePanel({ title, description, children }: CreatePanelProps) {
  return (
    <div className={cn(panelClass, "fh-reveal overflow-hidden")}>
      <div className="border-b border-border px-5 py-4">
        <p className="eyebrow">Create</p>
        <h2 className="mt-2 text-lg font-medium tracking-tight text-foreground">{title}</h2>
        <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{description}</p>
      </div>
      <div className="p-5">{children}</div>
    </div>
  );
}
