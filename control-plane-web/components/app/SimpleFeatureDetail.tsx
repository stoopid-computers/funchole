"use client";

import { AdvancedLink } from "@/components/ModeSwitch";
import Link from "next/link";
import { useParams } from "next/navigation";
import useSWR from "swr";
import { Button } from "@/components/Button";
import { PageLoading } from "@/components/PageLoading";
import { StatusBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { api } from "@/lib/api";
import { summarizeBuildFailure } from "@/lib/buildlog";
import { friendlyError } from "@/lib/errors";
import { fixPrompt } from "@/lib/launch";
import { timeAgo } from "@/lib/time";

// Simple-mode feature detail: is it ready, and if an update failed, why, and
// what to tell the agent. Source, artifacts and runtime live in Advanced.
export function SimpleFeatureDetail() {
  const { functionId } = useParams<{ functionId: string }>();
  const toast = useToast();
  const feature = useSWR(["function", functionId], () => api.getFunction(functionId));
  const versions = useSWR(["function-versions-detail", functionId], () => api.listFunctionVersions(functionId, 1, 10), {
    refreshInterval: 8000,
  });

  const items = versions.data?.items ?? [];
  const latest = items[0];
  const failedLatest = latest?.status === "FAILED" ? latest : undefined;
  const logs = useSWR(failedLatest ? ["build-logs", functionId, failedLatest.id] : null, () =>
    api.getFunctionVersionBuildLogs(functionId, failedLatest!.id)
  );

  const loadError = feature.error ?? versions.error;
  if (!feature.data || !versions.data) {
    return <PageLoading error={loadError ? friendlyError(loadError, "We couldn't load this feature.") : null} />;
  }

  const failure = summarizeBuildFailure(logs.data);

  async function copyFix() {
    try {
      await navigator.clipboard.writeText(fixPrompt(feature.data!.name));
      toast("Request copied. Paste it into your coding agent.");
    } catch {
      toast("Copy failed. Try again.");
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <div>
        <Link href="/functions" className="text-sm font-semibold text-brand underline underline-offset-4">
          ← Features
        </Link>
        <h1 className="display mt-3 text-3xl text-foreground sm:text-[2.5rem]">{feature.data.name}</h1>
        <div className="mt-3 flex items-center gap-3">
          {latest ? <StatusBadge status={latest.status} /> : <span className="text-sm text-muted-foreground">No updates yet</span>}
          {latest && <span className="text-sm text-muted-foreground">Updated {timeAgo(latest.updatedAt)}</span>}
        </div>
      </div>

      {failedLatest && (
        <section className="sticker flex flex-col items-start gap-3 rounded-2xl bg-coral-soft p-6" role="alert">
          <h2 className="font-heading text-xl font-extrabold tracking-tight text-foreground">The latest update needs attention</h2>
          <p className="max-w-2xl text-[15px] text-foreground">
            {failure?.summary ?? "It didn't publish."} Your agent can fix it. Copy this request and paste it into your agent.
          </p>
          <Button variant="primary" onClick={copyFix}>
            Copy request to fix it
          </Button>
          {failure?.technical && (
            <details className="w-full">
              <summary className="cursor-pointer text-sm font-semibold text-foreground underline underline-offset-4">Show what went wrong</summary>
              <pre className="mt-2 max-h-56 overflow-auto rounded-lg border border-island-line bg-island p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap text-island-fg">
                {failure.technical}
              </pre>
            </details>
          )}
        </section>
      )}

      <section aria-labelledby="updates" className="flex flex-col gap-3">
        <h2 id="updates" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
          Updates
        </h2>
        {items.length === 0 ? (
          <p className="text-[15px] text-muted-foreground">Nothing yet. Updates appear as your agent works on this feature.</p>
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
            {items.map((version) => (
              <li key={version.id} className="flex items-center justify-between gap-3 px-5 py-3">
                <div>
                  <p className="font-semibold text-foreground">Update {version.version}</p>
                  <p className="text-sm text-muted-foreground">{timeAgo(version.createdAt)}</p>
                </div>
                <StatusBadge status={version.status} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <AdvancedLink />
    </div>
  );
}
