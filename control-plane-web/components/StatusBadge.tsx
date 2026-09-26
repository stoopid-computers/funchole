import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

// Landing-page status pills: mono, uppercase, tinted - green for healthy,
// amber for not-yet-live, blue (pulsing) for in-progress, red for failure.
const TONES = {
  ok: "bg-success/10 text-success",
  pending: "bg-warning/10 text-warning",
  live: "bg-brand-soft text-brand",
  bad: "bg-danger/10 text-danger",
  neutral: "bg-white/[0.06] text-muted-foreground",
} as const;

const STATUS_TONE: Record<string, keyof typeof TONES> = {
  ACTIVE: "ok",
  VERIFIED: "ok",
  ADOPTED: "ok",
  READY: "ok",
  COMPLETED: "ok",
  PENDING: "pending",
  DRAFT: "pending",
  PUBLISHING: "live",
  FAILED: "bad",
  REVOKED: "bad",
  REJECTED: "bad",
  EXPIRED: "bad",
  ARCHIVED: "neutral",
  INACTIVE: "neutral",
};

export function StatusBadge({ status }: { status: string }) {
  const tone = STATUS_TONE[status] ?? "neutral";
  return (
    <Badge
      variant="outline"
      className={cn(
        "h-auto gap-1.5 rounded-md border-transparent px-1.5 py-0.5 font-mono text-[10px] font-medium tracking-wider uppercase",
        TONES[tone]
      )}
    >
      {tone === "live" ? <span className="live-dot" aria-hidden="true" /> : <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {status}
    </Badge>
  );
}
