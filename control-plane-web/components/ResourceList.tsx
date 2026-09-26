import type { ReactNode } from "react";
import { Panel } from "@/components/Panel";

interface ResourceListProps {
  title: string;
  description?: string;
  children: ReactNode;
}

export function ResourceList({ title, description, children }: ResourceListProps) {
  return (
    <Panel className="overflow-hidden">
      <div className="border-b border-border px-5 py-4">
        <h2 className="text-base font-medium tracking-tight text-foreground">{title}</h2>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      <div className="divide-y divide-border">{children}</div>
    </Panel>
  );
}

// Loading, empty and error messages inside a ResourceList.
export function ResourceListState({ children }: { children: ReactNode }) {
  return <div className="px-5 py-12 text-center text-sm text-muted-foreground">{children}</div>;
}
