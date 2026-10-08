"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { AskAgentEmpty } from "@/components/AskAgentEmpty";
import { FormError } from "@/components/FormError";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { Panel } from "@/components/Panel";
import { Chip } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { friendlyError } from "@/lib/errors";
import { timeAgo } from "@/lib/time";
import type { InvocationActivity } from "@/lib/types";

const PAGE_SIZE = 20;
type Filter = "gateway" | "test" | undefined;
const FILTERS: { value: Filter; label: string }[] = [
  { value: undefined, label: "Everything" },
  { value: "gateway", label: "Visitors" },
  { value: "test", label: "Your tests" },
];

function worked(item: InvocationActivity) {
  if (item.httpStatus !== null) return item.httpStatus < 400;
  return item.status === "COMPLETED";
}

function outcome(item: InvocationActivity) {
  if (item.status === "PENDING" || item.status === "RUNNING") return <Chip tone="pending">Running</Chip>;
  return worked(item) ? <Chip tone="ok">Worked</Chip> : <Chip tone="bad">Needs attention</Chip>;
}

function duration(ms: number | null) {
  if (ms === null) return "";
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

export default function ActivityPage() {
  const [filter, setFilter] = useState<Filter>(undefined);
  const [page, setPage] = useState(1);
  const summary = useSWR("activity-summary", () => api.getActivitySummary());
  const list = useSWR(["activity", filter, page], () => api.listActivity(page, PAGE_SIZE, filter), { refreshInterval: 15000 });

  const error = summary.error ?? list.error;
  const nothingYet = list.data && list.data.totalElements === 0 && filter === undefined;

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Activity" description="Who visited your pages and APIs, and whether it worked." />

      {error && <FormError>{friendlyError(error, "We couldn't load your activity.")}</FormError>}

      {summary.data && (
        <section aria-label="Last 24 hours" className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <Panel className="p-4 sm:p-5">
            <p className="text-sm font-semibold text-muted-foreground">Visits, last 24 hours</p>
            <p className="font-heading text-3xl font-extrabold tracking-tight text-foreground">{summary.data.requests24h}</p>
          </Panel>
          <Panel className="p-4 sm:p-5">
            <p className="text-sm font-semibold text-muted-foreground">Needed attention</p>
            <p className="font-heading text-3xl font-extrabold tracking-tight text-foreground">{summary.data.failed24h}</p>
          </Panel>
          <Panel className="col-span-2 p-4 sm:col-span-1 sm:p-5">
            <p className="text-sm font-semibold text-muted-foreground">Last visit</p>
            <p className="font-heading text-xl font-extrabold tracking-tight text-foreground">
              {summary.data.lastRequestAt ? timeAgo(summary.data.lastRequestAt) : "None yet"}
            </p>
          </Panel>
        </section>
      )}

      {summary.data && summary.data.pages.length > 0 && (
        <section aria-labelledby="by-page" className="flex flex-col gap-3">
          <h2 id="by-page" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">By page</h2>
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
            {summary.data.pages.map((p) => (
              <li key={p.flowId} className="flex flex-wrap items-center justify-between gap-2 px-5 py-3">
                <Link href={`/flows/${p.flowId}`} className="font-semibold text-foreground hover:underline">
                  {p.flowName ?? p.flowKey}
                </Link>
                <p className="text-sm text-muted-foreground">
                  {p.requests24h} visit{p.requests24h === 1 ? "" : "s"}
                  {p.failed24h > 0 && `, ${p.failed24h} needed attention`}
                  {p.lastRequestAt && ` · last ${timeAgo(p.lastRequestAt)}`}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="recent" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="recent" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">Recent</h2>
          <div role="group" aria-label="Filter activity" className="flex gap-1.5">
            {FILTERS.map((f) => (
              <button
                key={f.label}
                type="button"
                aria-pressed={filter === f.value}
                onClick={() => { setFilter(f.value); setPage(1); }}
                className={`rounded-full border px-3 py-1 text-sm font-semibold ${filter === f.value ? "border-edge bg-ink text-background" : "border-border bg-card text-muted-foreground hover:text-foreground"}`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {!list.data && !list.error && <p className="text-sm text-muted-foreground">Loading…</p>}

        {nothingYet && (
          <AskAgentEmpty
            title="No visits yet"
            description="When someone opens one of your pages or calls your API, it shows up here."
          />
        )}

        {list.data && list.data.items.length > 0 && (
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
            {list.data.items.map((item) => (
              <li key={item.invocationId} className="flex flex-wrap items-center justify-between gap-2 px-5 py-3">
                <div className="min-w-0">
                  <p className="truncate font-semibold text-foreground">{item.flowName ?? item.flowKey ?? "Unknown page"}</p>
                  <p className="text-sm text-muted-foreground">
                    {item.source === "TEST" ? "Your test" : "Visitor"} · {timeAgo(item.createdAt)}
                    {item.durationMs !== null && ` · ${duration(item.durationMs)}`}
                  </p>
                </div>
                {outcome(item)}
              </li>
            ))}
          </ul>
        )}

        {list.data && list.data.totalElements > 0 && (
          <Pagination page={list.data.page} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onChange={setPage} />
        )}

        <p className="text-sm text-muted-foreground">
          Pages that are plain files aren&apos;t counted yet. Only requests that run your logic show up here.
        </p>
      </section>
    </div>
  );
}
