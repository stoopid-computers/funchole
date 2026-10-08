import { Fragment } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { cn } from "@/lib/utils";

interface FunctionLifecycleDiagramProps {
  status: string;
  hasSource: boolean;
  hasArtifact: boolean;
}

// Source to a runnable, testable build, drawn like the landing page's flow
// diagram: cards joined by dashed connectors.
export function FunctionLifecycleDiagram({ status, hasSource, hasArtifact }: FunctionLifecycleDiagramProps) {
  const stages = [
    { key: "source", label: "Source", active: hasSource, detail: hasSource ? "Submitted" : "Missing" },
    { key: "artifact", label: "Artifact", active: hasArtifact, detail: hasArtifact ? "Published" : "Not built" },
    { key: "ready", label: "Ready", active: status === "READY", detail: status.charAt(0) + status.slice(1).toLowerCase() },
    { key: "test", label: "Test", active: status === "READY", detail: status === "READY" ? "Available" : "Blocked" },
  ];

  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-card p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="eyebrow">Function lifecycle</p>
          <h2 className="mt-1 font-heading text-lg font-extrabold tracking-tight text-foreground">Source to runnable artifact</h2>
        </div>
        <StatusBadge status={status} />
      </div>

      <ol className={cn("mt-5 flex flex-col sm:flex-row sm:items-stretch", status === "PUBLISHING" && "fh-pulse")}>
        {stages.map((stage, index) => (
          <Fragment key={stage.key}>
            {index > 0 && (
              <li
                aria-hidden="true"
                className="mx-auto h-5 w-0 self-center border-l-[3px] border-dashed border-edge sm:h-0 sm:w-6 sm:border-t-[3px] sm:border-l-0"
              />
            )}
            <li
              className={cn(
                "flex-1 rounded-xl border-2 p-3",
                stage.active ? "border-edge bg-live-soft" : "border-dashed border-border-strong bg-muted"
              )}
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    "grid size-5 place-items-center rounded-full border-2 text-[11px] font-extrabold",
                    stage.active ? "border-edge bg-live text-[var(--fh-on-sun)]" : "border-border-strong text-transparent"
                  )}
                  aria-hidden="true"
                >
                  ✓
                </span>
                <p className="text-sm font-semibold text-foreground">{stage.label}</p>
              </div>
              <p className={cn("mt-1 text-xs", stage.active ? "text-live-ink" : "text-muted-foreground")}>{stage.detail}</p>
            </li>
          </Fragment>
        ))}
      </ol>
    </div>
  );
}
