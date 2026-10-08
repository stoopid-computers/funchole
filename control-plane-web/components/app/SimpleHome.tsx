"use client";

import Link from "next/link";
import { useEffect, type ReactNode } from "react";
import { PromptPicker } from "@/components/app/PromptPicker";
import { Button } from "@/components/Button";
import { CopyableLink } from "@/components/CopyableLink";
import { FormError } from "@/components/FormError";
import { DatabaseIcon, GlobeIcon, KeyIcon } from "@/components/icons";
import { PageHeader } from "@/components/PageHeader";
import { Chip, StatusBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { useApiKeys, useFlows, useFunctions, useFunctionVersions, useGateways } from "@/lib/data";
import { trackOnce } from "@/lib/analytics";
import { friendlyError } from "@/lib/errors";
import { fixPrompt, launchState, type LaunchState } from "@/lib/launch";
import { useProfile } from "@/lib/profile";
import type { FlowResponse, GatewayResponse } from "@/lib/types";
import { liveUrl } from "@/lib/urls";
import { cn } from "@/lib/utils";

const STAGE_TITLE: Record<LaunchState["stage"], string> = {
  connect: "Let's get your first thing live",
  "waiting-agent": "Almost there, connect your agent",
  ask: "Your agent is ready. What should it build?",
  building: "Your site is being built",
  live: "Your site is live",
};

const STAGE_DESCRIPTION: Record<LaunchState["stage"], string> = {
  connect: "You tell a coding agent what you need. FuncHole puts what it builds on the web.",
  "waiting-agent": "Paste one command into your coding agent and this page will notice.",
  ask: "Pick an example, copy the request and paste it into your agent.",
  building: "Your agent is working. Pages appear here as soon as they are published.",
  live: "Visitors can open it right now. Ask your agent for changes any time.",
};

export function SimpleHome() {
  const { profile } = useProfile();
  const toast = useToast();
  const keys = useApiKeys();
  const gateways = useGateways();
  const flows = useFlows();
  const functions = useFunctions();
  const functionItems = functions.data?.items;
  const versions = useFunctionVersions((functionItems ?? []).slice(0, 3).map((fn) => fn.id));

  const error = keys.error ?? gateways.error ?? flows.error ?? functions.error;
  const loading = !keys.data || !flows.data || !functions.data;
  const state = launchState({
    keys: keys.data,
    functions: functionItems,
    flows: flows.data?.items,
    versions: versions.data,
  });
  // Where people stall on the way to a live page, e.g. onboarding_step_waiting_agent.
  useEffect(() => {
    if (!loading) trackOnce(`onboarding_step_${state.stage.replace("-", "_")}`);
  }, [loading, state.stage]);
  const gateway = gateways.data?.items[0];
  const firstName = (profile?.fullName || profile?.username || "").split(" ")[0];

  function retry() {
    void Promise.all([keys.mutate(), gateways.mutate(), flows.mutate(), functions.mutate()]);
  }

  async function copyFix(functionName: string) {
    try {
      await navigator.clipboard.writeText(fixPrompt(functionName));
      toast("Request copied. Paste it into your coding agent.");
    } catch {
      toast("Copy failed. Try again.");
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        eyebrow={firstName ? `Hi ${firstName}` : undefined}
        title={loading ? "Welcome" : STAGE_TITLE[state.stage]}
        description={loading ? "Loading your workspace…" : STAGE_DESCRIPTION[state.stage]}
      />

      {error && (
        <FormError>
          {friendlyError(error, "We couldn't load your workspace.")}{" "}
          <button type="button" className="font-semibold underline underline-offset-4" onClick={retry}>
            Try again
          </button>
        </FormError>
      )}

      {!loading && state.stage !== "live" && <LaunchChecklist state={state} onCopyFix={copyFix} />}
      {!loading && state.stage === "live" && <LiveSite state={state} gateway={gateway} flows={flows.data?.items ?? []} />}

      {!loading && state.stage === "live" && (
        <section aria-labelledby="build-more" className="flex flex-col gap-4">
          <h2 id="build-more" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
            Build something else
          </h2>
          <PromptPicker />
        </section>
      )}
    </div>
  );
}

const STEPS = [
  { key: "agent", title: "Connect your coding agent" },
  { key: "ask", title: "Ask it to build something" },
  { key: "publish", title: "Watch it get published" },
  { key: "live", title: "Open your live site" },
] as const;

const CURRENT_STEP: Record<LaunchState["stage"], number> = { connect: 0, "waiting-agent": 0, ask: 1, building: 2, live: 4 };

function LaunchChecklist({ state, onCopyFix }: { state: LaunchState; onCopyFix: (functionName: string) => void }) {
  const current = CURRENT_STEP[state.stage];

  return (
    <ol className="flex flex-col gap-4">
      {STEPS.map((step, index) => {
        const done = index < current;
        const active = index === current;
        return (
          <li
            key={step.key}
            aria-current={active ? "step" : undefined}
            className={cn(
              "rounded-2xl border-2 p-5",
              active ? "sticker bg-card" : done ? "border-live-ink/40 bg-live-soft" : "border-dashed border-border bg-muted/60"
            )}
          >
            <div className="flex items-center gap-4">
              <span
                aria-hidden="true"
                className={cn(
                  "grid size-9 shrink-0 place-items-center rounded-full border-2 font-heading text-base font-extrabold",
                  done ? "border-edge bg-live text-[var(--fh-on-sun)]" : active ? "border-edge bg-sun text-[var(--fh-on-sun)]" : "border-border-strong text-muted-foreground"
                )}
              >
                {done ? "✓" : index + 1}
              </span>
              <h2 className={cn("font-heading text-xl font-extrabold tracking-tight", active || done ? "text-foreground" : "text-muted-foreground")}>
                {step.title}
              </h2>
            </div>
            {active && (
              <div className="mt-4 sm:pl-[3.25rem]">
                <StepBody state={state} onCopyFix={onCopyFix} />
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}

function StepBody({ state, onCopyFix }: { state: LaunchState; onCopyFix: (functionName: string) => void }) {
  switch (state.stage) {
    case "connect":
      return (
        <div className="flex flex-col items-start gap-3">
          <p className="max-w-xl text-[15px] text-muted-foreground">
            A coding agent such as Claude Code or Codex writes your site. Connecting it takes two minutes.
          </p>
          <Button variant="primary" size="lg" asChild>
            <Link href="/api-keys">Connect your agent</Link>
          </Button>
        </div>
      );
    case "waiting-agent":
      return (
        <div className="flex flex-col items-start gap-3">
          <Chip tone="pending">Waiting for your agent…</Chip>
          <p className="max-w-xl text-[15px] text-muted-foreground">
            Your key is ready. Paste the setup command into your agent. This page updates on its own when it connects.
          </p>
          <Button variant="card" asChild>
            <Link href="/api-keys">Show setup again</Link>
          </Button>
        </div>
      );
    case "ask":
      return (
        <div className="flex flex-col gap-4">
          <p className="max-w-xl text-[15px] text-muted-foreground">Pick an example. We copy a request that you paste into your coding agent.</p>
          <PromptPicker />
        </div>
      );
    case "building":
      return <PublishProgress state={state} onCopyFix={onCopyFix} />;
    default:
      return null;
  }
}

function PublishProgress({ state, onCopyFix }: { state: LaunchState; onCopyFix: (functionName: string) => void }) {
  const build = state.build;
  if (!build) return null;

  return (
    <div className="flex flex-col items-start gap-3">
      {build.state === "drafting" && (
        <>
          <Chip tone="pending">Your agent is working…</Chip>
          <p className="max-w-xl text-[15px] text-muted-foreground">It has started on your site. This page updates when something is published.</p>
        </>
      )}
      {build.state === "publishing" && (
        <>
          <Chip tone="live" pulse>
            Publishing…
          </Chip>
          <p className="max-w-xl text-[15px] text-muted-foreground">
            {build.functionName ? `“${build.functionName}” is being published.` : "Your site is being published."} This usually takes a minute.
          </p>
        </>
      )}
      {build.state === "ready" && (
        <>
          <Chip tone="ok">Ready. Going live…</Chip>
          <p className="max-w-xl text-[15px] text-muted-foreground">The code is published. Your agent is switching the page on.</p>
        </>
      )}
      {build.state === "failed" && (
        <>
          <Chip tone="bad">Needs attention</Chip>
          <p className="max-w-xl text-[15px] text-muted-foreground">
            {build.functionName ? `“${build.functionName}”` : "The latest update"} didn&apos;t publish. Your agent can fix it. Copy this request and paste it into your agent.
          </p>
          <Button variant="primary" onClick={() => onCopyFix(build.functionName ?? "your feature")}>
            Copy request to fix it
          </Button>
        </>
      )}
    </div>
  );
}

function LiveSite({ state, gateway, flows }: { state: LaunchState; gateway: GatewayResponse | undefined; flows: FlowResponse[] }) {
  const primary = state.liveFlows.find((flow) => flow.path === "/") ?? state.liveFlows[0];
  const siteUrl = gateway && primary ? liveUrl(gateway, primary.path.includes(":") || primary.path.includes("*") ? "" : primary.path) : null;
  const drafts = flows.filter((flow) => flow.activeFlowVersionStatus !== "ADOPTED");

  return (
    <div className="flex flex-col gap-8">
      <section className="sticker flex flex-col gap-4 rounded-2xl bg-card p-6">
        <Chip tone="ok" pulse>
          Live
        </Chip>
        {siteUrl ? (
          <>
            <p className="font-heading text-2xl font-extrabold tracking-tight break-all text-foreground sm:text-3xl">{siteUrl.replace("https://", "").replace(/\/$/, "")}</p>
            <div className="flex flex-wrap items-center gap-3">
              <Button variant="primary" size="lg" asChild>
                <a href={siteUrl} target="_blank" rel="noreferrer">
                  Open your site
                </a>
              </Button>
              <CopyableLink href={siteUrl}>Copy link</CopyableLink>
            </div>
          </>
        ) : (
          <p className="text-[15px] text-muted-foreground">Your site is live.</p>
        )}
      </section>

      <section aria-labelledby="live-pages" className="flex flex-col gap-3">
        <h2 id="live-pages" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
          What visitors can open
        </h2>
        <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
          {[...state.liveFlows, ...drafts].map((flow) => (
            <li key={flow.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
              <div className="min-w-0">
                <p className="font-semibold text-foreground">{flow.name}</p>
                {gateway && flow.activeFlowVersionStatus === "ADOPTED" ? (
                  <CopyableLink href={liveUrl(gateway, flow.path)}>{flow.path}</CopyableLink>
                ) : (
                  <p className="text-sm text-muted-foreground">{flow.path}</p>
                )}
              </div>
              <div className="flex items-center gap-3">
                <StatusBadge status={flow.activeFlowVersionStatus === "ADOPTED" ? "ADOPTED" : "DRAFT"} />
                <Link href={`/flows/${flow.id}`} className="text-sm font-semibold text-brand underline underline-offset-4">
                  Details
                </Link>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="next-steps" className="flex flex-col gap-3">
        <h2 id="next-steps" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
          What&apos;s next
        </h2>
        <div className="grid gap-4 sm:grid-cols-3">
          <NextStep href={gateway ? `/gateways/${gateway.id}` : "/settings"} icon={<GlobeIcon className="h-5 w-5" />} title="Use your own domain">
            Show your site at a name like shop.yourbusiness.com.
          </NextStep>
          <NextStep href="/environments" icon={<KeyIcon className="h-5 w-5" />} title="Add a private key">
            Keep passwords and keys safe. Your agent tells you when it needs one.
          </NextStep>
          <NextStep href="/databases" icon={<DatabaseIcon className="h-5 w-5" />} title="See your database">
            Where your pages keep their data, like bookings or orders.
          </NextStep>
        </div>
      </section>
    </div>
  );
}

function NextStep({ href, icon, title, children }: { href: string; icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="group flex flex-col gap-2 rounded-xl border border-border bg-card p-5 transition-colors hover:bg-sun-soft"
    >
      <span className="grid size-10 place-items-center rounded-lg border border-border bg-muted text-foreground">{icon}</span>
      <span className="font-heading text-lg font-extrabold tracking-tight text-foreground">{title}</span>
      <span className="text-sm text-muted-foreground">{children}</span>
    </Link>
  );
}
