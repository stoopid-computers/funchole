import { StatusBadge } from "@/components/StatusBadge";

interface FunctionLifecycleDiagramProps {
  status: string;
  hasSource: boolean;
  hasArtifact: boolean;
}

export function FunctionLifecycleDiagram({ status, hasSource, hasArtifact }: FunctionLifecycleDiagramProps) {
  const stages = [
    { key: "source", label: "Source", active: hasSource, detail: hasSource ? "submitted" : "missing" },
    { key: "artifact", label: "Artifact", active: hasArtifact, detail: hasArtifact ? "published" : "not built" },
    { key: "ready", label: "Ready", active: status === "READY", detail: status.toLowerCase() },
    { key: "test", label: "Test", active: status === "READY", detail: status === "READY" ? "available" : "blocked" },
  ];

  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-background p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="eyebrow">Function lifecycle</p>
          <h2 className="mt-2 text-base font-medium tracking-tight text-foreground">Source to runnable artifact</h2>
        </div>
        <StatusBadge status={status} />
      </div>

      <div className="relative mt-6">
        <svg viewBox="0 0 820 160" fill="none" className="hidden w-full sm:block">
          <path d="M112 78H708" stroke="rgba(255,255,255,0.12)" strokeWidth="2" strokeLinecap="round" />
          <path
            d="M112 78H708"
            stroke="rgba(107,140,255,0.72)"
            strokeWidth="3"
            strokeLinecap="round"
            strokeDasharray="12 12"
            className={status === "PUBLISHING" ? "fh-pulse" : ""}
          />
          {stages.map((stage, index) => {
            const x = 112 + index * 198;
            return (
              <g key={stage.key}>
                <circle cx={x} cy="78" r="27" fill={stage.active ? "rgba(107,140,255,0.16)" : "rgba(255,255,255,0.04)"} stroke={stage.active ? "rgba(107,140,255,0.6)" : "rgba(255,255,255,0.12)"} strokeWidth="2" />
                <circle cx={x} cy="78" r="7" fill={stage.active ? "#6b8cff" : "rgba(255,255,255,0.22)"} />
              </g>
            );
          })}
        </svg>

        <div className="grid gap-3 sm:grid-cols-4 sm:-mt-4">
          {stages.map((stage) => (
            <div key={stage.key} className="rounded-xl border border-border bg-surface/75 p-3">
              <div className="flex items-center gap-2">
                <span className={`h-2.5 w-2.5 rounded-full ${stage.active ? "bg-brand" : "bg-faint"}`} />
                <p className="text-sm font-semibold text-foreground">{stage.label}</p>
              </div>
              <p className="mt-1 font-mono text-[11px] uppercase tracking-[0.14em] text-muted-foreground">{stage.detail}</p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
