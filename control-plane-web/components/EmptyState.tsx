import type { ReactNode } from "react";

interface EmptyStateProps {
  title: string;
  description?: string;
  action?: ReactNode;
}

// "Nothing here yet" block: says what's missing and how to get it.
export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div className="p-5">
      <div className="flex flex-col items-center rounded-xl border border-dashed border-border-strong px-6 py-10 text-center">
        <p className="text-sm font-medium text-foreground">{title}</p>
        {description && <p className="mt-1 max-w-sm text-sm leading-relaxed text-muted-foreground">{description}</p>}
        {action && <div className="mt-4">{action}</div>}
      </div>
    </div>
  );
}
