"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import useSWR from "swr";
import { Button } from "@/components/Button";
import { confirmAction } from "@/components/ConfirmDialog";
import { CopyableLink } from "@/components/CopyableLink";
import { FormError } from "@/components/FormError";
import { PageLoading } from "@/components/PageLoading";
import { Chip, StatusBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { api } from "@/lib/api";
import { useGateways } from "@/lib/data";
import { friendlyError } from "@/lib/errors";
import { fixPagePrompt } from "@/lib/launch";
import { useMode } from "@/lib/mode";
import type { InvocationInspectionResponse } from "@/lib/types";
import { liveUrl } from "@/lib/urls";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

type TryResult = { kind: "ok" | "failed" | "slow"; invocation?: InvocationInspectionResponse };

// Simple-mode page detail: is it live, where is it, switch it on or off, and a
// "Try it" button. Versions, steps and attachments live in Advanced.
export function SimplePageDetail() {
  const { flowId } = useParams<{ flowId: string }>();
  const { setMode } = useMode();
  const toast = useToast();
  const gateways = useGateways();
  const flow = useSWR(["flow", flowId], () => api.getFlow(flowId));
  const versions = useSWR(["flow-versions", flowId], () => api.listFlowVersions(flowId, 1, 20));

  const [busy, setBusy] = useState(false);
  const [trying, setTrying] = useState(false);
  const [result, setResult] = useState<TryResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadError = flow.error ?? versions.error;
  if (!flow.data || !versions.data) {
    return <PageLoading error={loadError ? friendlyError(loadError, "We couldn't load this page.") : null} />;
  }

  const page = flow.data;
  const items = versions.data.items;
  const adopted = items.find((version) => version.status === "ADOPTED");
  const draft = items.find((version) => version.status === "DRAFT");
  const gateway = gateways.data?.items.find((item) => item.id === page.gatewayId);
  const url = gateway && adopted ? liveUrl(gateway, page.path) : null;

  async function goLive() {
    if (!draft) return;
    setError(null);
    setBusy(true);
    try {
      await api.adoptFlowVersion(flowId, draft.id);
      await Promise.all([flow.mutate(), versions.mutate()]);
      toast("Your page is live.");
    } catch (err) {
      setError(friendlyError(err, "We couldn't switch this page on."));
    } finally {
      setBusy(false);
    }
  }

  async function takeOffline() {
    if (!adopted) return;
    const confirmed = await confirmAction({
      title: `Take "${page.name}" offline?`,
      description: "Visitors won't be able to open it until you switch it back on.",
      confirmLabel: "Take offline",
    });
    if (!confirmed) return;
    setError(null);
    setBusy(true);
    try {
      await api.archiveFlowVersion(flowId, adopted.id);
      await Promise.all([flow.mutate(), versions.mutate()]);
      toast("Your page is offline.");
    } catch (err) {
      setError(friendlyError(err, "We couldn't take this page offline."));
    } finally {
      setBusy(false);
    }
  }

  async function tryIt() {
    const version = adopted ?? draft ?? items[0];
    if (!version) return;
    setError(null);
    setResult(null);
    setTrying(true);
    try {
      const started = await api.invokeFlowVersion(flowId, version.id, "{}");
      for (let attempt = 0; attempt < 12; attempt += 1) {
        await sleep(1000);
        const invocation = await api.getInvocation(started.invocationId);
        if (invocation.status === "COMPLETED") return setResult({ kind: "ok", invocation });
        if (invocation.status === "FAILED") return setResult({ kind: "failed", invocation });
      }
      setResult({ kind: "slow" });
    } catch (err) {
      setError(friendlyError(err, "We couldn't try this page."));
    } finally {
      setTrying(false);
    }
  }

  async function copyFix() {
    try {
      await navigator.clipboard.writeText(fixPagePrompt(page.name));
      toast("Request copied. Paste it into your coding agent.");
    } catch {
      toast("Copy failed. Try again.");
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <div>
        <Link href="/flows" className="text-sm font-semibold text-brand underline underline-offset-4">
          ← Pages &amp; APIs
        </Link>
        <h1 className="display mt-3 text-3xl text-foreground sm:text-[2.5rem]">{page.name}</h1>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <StatusBadge status={adopted ? "ADOPTED" : "DRAFT"} />
          {!adopted && <span className="text-sm text-muted-foreground">Not live yet. Visitors can&apos;t open it.</span>}
        </div>
      </div>

      {error && <FormError>{error}</FormError>}

      {url && (
        <section className="sticker flex flex-col gap-3 rounded-2xl bg-card p-6">
          <p className="text-sm font-semibold text-muted-foreground">Web address</p>
          <p className="font-heading text-xl font-extrabold tracking-tight break-all text-foreground sm:text-2xl">{url.replace("https://", "")}</p>
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="primary" asChild>
              <a href={url} target="_blank" rel="noreferrer">
                Open page
              </a>
            </Button>
            <CopyableLink href={url}>Copy link</CopyableLink>
          </div>
        </section>
      )}

      <section className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-6">
        <h2 className="font-heading text-xl font-extrabold tracking-tight text-foreground">What do you want to do?</h2>
        <div className="flex flex-wrap items-center gap-3">
          {adopted ? (
            <Button variant="danger" disabled={busy} onClick={takeOffline}>
              Take offline
            </Button>
          ) : draft ? (
            <Button variant="primary" disabled={busy} onClick={goLive}>
              {busy ? "Switching on…" : "Go live"}
            </Button>
          ) : (
            <p className="text-[15px] text-muted-foreground">There is nothing to switch on yet. Ask your agent to finish this page.</p>
          )}
          <Button variant="secondary" disabled={trying || items.length === 0} onClick={tryIt}>
            {trying ? "Trying…" : "Try it"}
          </Button>
        </div>

        {result?.kind === "ok" && (
          <div className="flex flex-col gap-2" role="status">
            <Chip tone="ok">It worked</Chip>
            {result.invocation?.result && (
              <pre className="max-h-64 overflow-auto rounded-lg border border-island-line bg-island p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap text-island-fg">
                {result.invocation.result.slice(0, 2000)}
              </pre>
            )}
          </div>
        )}
        {result?.kind === "failed" && (
          <div className="flex flex-col items-start gap-2" role="status">
            <Chip tone="bad">It didn&apos;t work</Chip>
            <p className="text-[15px] text-muted-foreground">Your agent can fix it. Copy this request and paste it into your agent.</p>
            <Button variant="primary" onClick={copyFix}>
              Copy request to fix it
            </Button>
          </div>
        )}
        {result?.kind === "slow" && (
          <div role="status">
            <Chip tone="pending">Still working…</Chip>
            <p className="mt-2 text-[15px] text-muted-foreground">It is taking longer than usual. Try again in a moment.</p>
          </div>
        )}
      </section>

      <p className="text-sm text-muted-foreground">
        Need the technical details?{" "}
        <button type="button" className="cursor-pointer font-semibold text-brand underline underline-offset-4" onClick={() => setMode("advanced")}>
          Switch to Advanced
        </button>
      </p>
    </div>
  );
}
