import { statusInfo, type StatusTone } from "@/lib/copy";
import { cn } from "@/lib/utils";

// Status chips from the landing page: a tinted pill, plain words ("Live",
// "Needs attention"), a pulsing dot while something is in progress. The raw
// backend value stays available as a tooltip.
const TONES: Record<StatusTone, string> = {
  ok: "border-live-ink/30 bg-live-soft text-live-ink",
  pending: "border-sun bg-sun-soft text-foreground",
  live: "border-brand/30 bg-brand-soft text-brand",
  bad: "border-danger/30 bg-coral-soft text-danger",
  neutral: "border-border bg-muted text-muted-foreground",
};

// A status pill with free-form text, for states that are not a backend enum
// (e.g. "Waiting for your agent…").
export function Chip({ tone, pulse, children }: { tone: StatusTone; pulse?: boolean; children: React.ReactNode }) {
  return (
    <span className={cn("inline-flex w-fit items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap", TONES[tone])}>
      {pulse ? <span className="live-dot" aria-hidden="true" /> : <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {children}
    </span>
  );
}

// `kind="certificate"` says "Secure" instead of repeating the gateway's "Live".
export function StatusBadge({ status, kind }: { status: string; kind?: "certificate" }) {
  const { label, tone } = statusInfo(status, kind);
  return (
    <span
      title={status}
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap",
        TONES[tone]
      )}
    >
      {tone === "live" ? <span className="live-dot" aria-hidden="true" /> : <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {label}
    </span>
  );
}
