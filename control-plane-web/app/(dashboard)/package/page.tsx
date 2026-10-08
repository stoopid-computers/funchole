"use client";

import useSWR from "swr";
import { FormError } from "@/components/FormError";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { Chip } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { friendlyError } from "@/lib/errors";
import { limitLabel, usageLevel, visibleLimits } from "@/lib/plan";
import { cn } from "@/lib/utils";

export default function PlanPage() {
  const { data, error } = useSWR("package-usage", () => api.getPackageUsage());

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Your plan" description="What your plan includes and how much of it you have used." />

      {error && <FormError>{friendlyError(error, "We couldn't load your plan.")}</FormError>}
      {!data && !error && <p className="text-sm text-muted-foreground">Loading…</p>}

      {data && !data.cloudMode && (
        <Panel className="p-6">
          <p className="font-heading text-xl font-extrabold tracking-tight text-foreground">No limits</p>
          <p className="mt-1 text-[15px] text-muted-foreground">This is a self-hosted FuncHole, so nothing is limited by a plan.</p>
        </Panel>
      )}

      {data?.cloudMode && (
        <>
          <section className="sticker flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-card p-6">
            <div>
              <p className="text-sm font-semibold text-muted-foreground">Current plan</p>
              <p className="font-heading text-3xl font-extrabold tracking-tight text-foreground">{data.packageName ?? "Free"}</p>
            </div>
            <Chip tone="ok">Active</Chip>
          </section>

          <section aria-labelledby="usage" className="flex flex-col gap-3">
            <h2 id="usage" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
              What you&apos;ve used
            </h2>
            <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
              {visibleLimits(data.limits).map((item) => {
                const level = usageLevel(item);
                const percent = item.limit ? Math.min(100, Math.round((item.used / item.limit) * 100)) : 0;
                return (
                  <li key={item.key} className="flex flex-col gap-2 px-5 py-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="font-semibold text-foreground">{limitLabel(item.key)}</p>
                      <div className="flex items-center gap-3">
                        <p className="text-sm text-muted-foreground">{item.limit === null ? `${item.used} used, no limit` : `${item.used} of ${item.limit}`}</p>
                        {level === "full" && <Chip tone="bad">At your limit</Chip>}
                        {level === "near" && <Chip tone="pending">Almost full</Chip>}
                      </div>
                    </div>
                    {item.limit !== null && (
                      <div
                        role="progressbar"
                        aria-label={limitLabel(item.key)}
                        aria-valuemin={0}
                        aria-valuemax={item.limit}
                        aria-valuenow={item.used}
                        className="h-3 overflow-hidden rounded-full border-2 border-edge bg-muted"
                      >
                        <div className={cn("h-full", level === "full" ? "bg-coral" : level === "near" ? "bg-sun" : "bg-live")} style={{ width: `${percent}%` }} />
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          </section>

          <p className="text-sm text-muted-foreground">Need more room? Get in touch and we&apos;ll help.</p>
        </>
      )}
    </div>
  );
}
