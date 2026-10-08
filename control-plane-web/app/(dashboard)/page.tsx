"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ComponentType } from "react";
import { useEffect, useMemo, useState } from "react";
import { CopyableLink } from "@/components/CopyableLink";
import { api } from "@/lib/api";
import type { FlowResponse, GatewayResponse } from "@/lib/types";
import { friendlyError } from "@/lib/errors";
import { useProfile } from "@/lib/profile";
import { useMode } from "@/lib/mode";
import { SimpleHome } from "@/components/app/SimpleHome";
import { FormError } from "@/components/FormError";
import { Panel } from "@/components/Panel";
import { PageHeader } from "@/components/PageHeader";
import { EmptyState } from "@/components/EmptyState";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/Button";
import { StatusBadge } from "@/components/StatusBadge";
import {
  DatabaseIcon,
  FunctionIcon,
  GlobeIcon,
  KeyIcon,
  PlayIcon,
  ServerIcon,
  TerminalIcon,
  WorkflowIcon,
  ZapIcon,
} from "@/components/icons";
import { gatewayHost, liveUrl } from "@/lib/urls";

interface AttentionItem {
  href: string;
  label: string;
  detail: string;
  icon: ComponentType<{ className?: string }>;
}

export default function OverviewPage() {
  const { mode } = useMode();
  return mode === "simple" ? <SimpleHome /> : <AdvancedOverview />;
}

function AdvancedOverview() {
  const router = useRouter();
  const { profile } = useProfile();
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [domainCount, setDomainCount] = useState<number | null>(null);
  const [gatewayCount, setGatewayCount] = useState<number | null>(null);
  const [flowCount, setFlowCount] = useState<number | null>(null);
  const [functionCount, setFunctionCount] = useState<number | null>(null);
  const [apiKeyCount, setApiKeyCount] = useState<number | null>(null);
  const [environmentCount, setEnvironmentCount] = useState<number | null>(null);
  const [databaseCount, setDatabaseCount] = useState<number | null>(null);
  const [flows, setFlows] = useState<FlowResponse[]>([]);
  const [gateways, setGateways] = useState<GatewayResponse[]>([]);

  useEffect(() => {
    let active = true;
    const fail = (err: unknown) => {
      if (active) setLoadError(friendlyError(err, "We couldn't load your workspace."));
    };
    api.listDomains(1, 1).then((r) => active && setDomainCount(r.totalElements)).catch(fail);
    api.listGateways(1, 5).then((r) => {
      if (!active) return;
      setGatewayCount(r.totalElements);
      setGateways(r.items);
    }).catch(fail);
    api.listFlows(1, 50).then((r) => {
      if (!active) return;
      setFlowCount(r.totalElements);
      setFlows(r.items);
    }).catch(fail);
    api.listFunctions(1, 1).then((r) => active && setFunctionCount(r.totalElements)).catch(fail);
    api.listApiKeys().then((r) => active && setApiKeyCount(r.filter((key) => !key.revokedAt).length)).catch(fail);
    api.listEnvironments(1, 1).then((r) => active && setEnvironmentCount(r.totalElements)).catch(fail);
    api.listDatabases(1, 1).then((r) => active && setDatabaseCount(r.totalElements)).catch(fail);
    return () => {
      active = false;
    };
  }, [reloadKey]);

  const gatewayById = useMemo(() => new Map(gateways.map((gateway) => [gateway.id, gateway])), [gateways]);

  const attentionCandidates: Array<AttentionItem | null> = [
    apiKeyCount !== null && apiKeyCount === 0
      ? { href: "/api-keys", label: "Connect an agent", detail: "Required before coding agents can safely work in this workspace.", icon: TerminalIcon }
      : null,
    domainCount !== null && domainCount === 0 && gatewayCount !== null && gatewayCount === 0
      ? { href: "/domains", label: "Add a custom domain", detail: "Needed before public URLs can run on your own hostname.", icon: GlobeIcon }
      : null,
    gatewayCount !== null && gatewayCount === 0
      ? { href: "/gateways", label: "Create an entry point", detail: "Entry points receive public requests and send them to workflows.", icon: ServerIcon }
      : null,
    functionCount !== null && functionCount === 0
      ? { href: "/functions", label: "Create first action", detail: "Actions are the reusable pieces your agent can prepare and test.", icon: FunctionIcon }
      : null,
    flowCount !== null && flowCount === 0
      ? { href: "/flows", label: "Create first workflow", detail: "Workflows connect public requests to the right actions.", icon: WorkflowIcon }
      : null,
  ];
  const attentionItems = attentionCandidates.filter((item): item is AttentionItem => item !== null);

  const coreMetrics = [
    { href: "/functions", label: "Actions", value: functionCount, icon: FunctionIcon },
    { href: "/flows", label: "Workflows", value: flowCount, icon: WorkflowIcon },
    { href: "/gateways", label: "Entry Points", value: gatewayCount, icon: ServerIcon },
    { href: "/domains", label: "Custom Domains", value: domainCount, icon: GlobeIcon },
  ].filter((metric) => metric.href !== "/domains" || !(domainCount === 0 && (gatewayCount ?? 0) > 0));

  const configMetrics = [
    { href: "/api-keys", label: "Agent Access", value: apiKeyCount, icon: TerminalIcon },
    { href: "/environments", label: "Variables & Secrets", value: environmentCount, icon: KeyIcon },
    { href: "/databases", label: "Data Sources", value: databaseCount, icon: DatabaseIcon },
  ];

  const activeRoutes = flows.filter((flow) => flow.activeFlowVersionStatus === "ADOPTED").slice(0, 4);
  const draftRoutes = flows.filter((flow) => flow.activeFlowVersionStatus !== "ADOPTED").slice(0, 3);

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        eyebrow="Control plane"
        title={`Operations overview${profile ? `, ${profile.username}` : ""}`}
        description="Current workspace state, live URLs, and manual checks. Setup guidance only appears when something needs attention."
        actions={
          <>
            {(functionCount ?? 0) > 0 && (
              <Button variant="primary" asChild>
                <Link href="/functions">
                  <PlayIcon className="h-4 w-4" />
                  Run a test
                </Link>
              </Button>
            )}
            <Button variant="secondary" asChild>
              <Link href="/api-keys">
                <TerminalIcon className="h-4 w-4" />
                Agent access
              </Link>
            </Button>
          </>
        }
      />

      {loadError && (
        <FormError>
          {loadError}{" "}
          <button type="button" className="font-medium underline underline-offset-4" onClick={() => {
              setLoadError(null);
              setReloadKey((key) => key + 1);
            }}>
            Try again
          </button>
        </FormError>
      )}

      <section className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        {coreMetrics.map((metric) => (
          <MetricCard key={metric.href} {...metric} />
        ))}
      </section>

      <section className="grid gap-4 xl:grid-cols-[1.35fr_0.65fr]">
        <Panel className="overflow-hidden">
          <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
            <div>
              <h2 className="flex items-center gap-2 text-base font-medium tracking-tight text-foreground">
                {activeRoutes.length > 0 && <span className="live-dot text-success" aria-hidden="true" />}
                Live URLs
              </h2>
              <p className="mt-1 text-sm text-muted-foreground">Public URLs that are ready to receive customer requests.</p>
            </div>
            <Link href="/flows" className="shrink-0 text-sm text-muted-foreground transition-colors hover:text-foreground">
              View workflows →
            </Link>
          </div>

          {activeRoutes.length > 0 ? (
            <div className="divide-y divide-border">
              {activeRoutes.map((flow) => {
                const gateway = gatewayById.get(flow.gatewayId);
                const hostname = gateway ? gatewayHost(gateway) : flow.gatewayName;
                const routeUrl = `https://${hostname}${flow.path}`;
                return (
                  // A plain div (not Link) wrapping the row: the route text
                  // below is a real external <a>, and an <a> can't legally
                  // nest inside another <a> - clicking anywhere else in the
                  // row still navigates internally via this onClick.
                  <div
                    key={flow.id}
                    role="link"
                    tabIndex={0}
                    onClick={() => router.push(`/flows/${flow.id}`)}
                    onKeyDown={(e) => e.key === "Enter" && router.push(`/flows/${flow.id}`)}
                    className="group grid cursor-pointer gap-3 px-5 py-4 transition-colors hover:bg-ink/3 lg:grid-cols-[1fr_auto]"
                  >
                    <div className="min-w-0">
                      <div className="flex min-w-0 items-center gap-2">
                        <span className="shrink-0 rounded-md bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">
                          {flow.httpMethod}
                        </span>
                        <code className="truncate font-mono text-[13px] text-foreground">
                          <CopyableLink href={routeUrl}>
                            <span className="text-subtle">https://{hostname}</span>
                            {flow.path}
                          </CopyableLink>
                        </code>
                      </div>
                      <p className="mt-2 text-sm font-medium text-foreground">{flow.name}</p>
                      <p className="mt-0.5 font-mono text-xs text-subtle">{flow.flowKey}</p>
                    </div>
                    <div className="flex items-center gap-3 lg:justify-end">
                      <StatusBadge status={flow.activeFlowVersionStatus ?? "DRAFT"} />
                      <span className="text-xs text-subtle transition-colors group-hover:text-foreground">Open →</span>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <EmptyState
              title="No live URLs yet"
              description="Publish a workflow when you are ready to expose a public URL."
              action={
                <Button variant="secondary" size="sm" asChild>
                  <Link href="/flows">Open workflows</Link>
                </Button>
              }
            />
          )}
        </Panel>

        <Panel className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-base font-medium tracking-tight text-foreground">Needs attention</h2>
              <p className="mt-1 text-sm text-muted-foreground">Only one-time or blocking setup appears here.</p>
            </div>
            <StatusBadge status={attentionItems.length === 0 ? "READY" : "PENDING"} />
          </div>

          <div className="mt-5 space-y-2">
            {attentionItems.length === 0 ? (
              <div className="rounded-xl border border-success/20 bg-success/[0.06] p-4">
                <p className="text-sm font-medium text-success">Workspace is configured.</p>
                <p className="mt-1 text-xs leading-5 text-muted-foreground">No setup checklist noise. Use the dashboard for operations and debugging.</p>
              </div>
            ) : (
              attentionItems.map((item) => {
                const Icon = item.icon;
                return (
                  <Link key={item.href} href={item.href} className="group flex gap-3 rounded-xl border border-border p-3 transition-colors hover:border-border-strong hover:bg-ink/3">
                    <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-border bg-secondary text-muted-strong">
                      <Icon className="h-4 w-4" />
                    </span>
                    <span className="min-w-0">
                      <span className="block text-sm font-medium text-foreground">{item.label}</span>
                      <span className="block text-xs leading-5 text-muted-foreground">{item.detail}</span>
                    </span>
                  </Link>
                );
              })
            )}
          </div>
        </Panel>
      </section>

      <section className="grid gap-4 xl:grid-cols-[0.8fr_1.2fr]">
        <Panel className="p-5">
          <h2 className="text-base font-medium tracking-tight text-foreground">Shared setup</h2>
          <p className="mt-1 text-sm text-muted-foreground">Access, variables, secrets, and data connections used by customer-facing work.</p>
          <div className="mt-4 overflow-hidden rounded-xl border border-border divide-y divide-border">
            {configMetrics.map((metric) => (
              <CompactMetric key={metric.href} {...metric} />
            ))}
          </div>
        </Panel>

        <Panel className="p-5">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 className="text-base font-medium tracking-tight text-foreground">Work in progress</h2>
              <p className="mt-1 text-sm text-muted-foreground">Items that exist but are not serving customer requests yet.</p>
            </div>
            <ZapIcon className="h-4 w-4 text-subtle" />
          </div>
          <div className="mt-4 space-y-2">
            {draftRoutes.length === 0 ? (
              <p className="rounded-xl border border-dashed border-border p-4 text-sm text-muted-foreground">No work-in-progress URLs in the latest snapshot.</p>
            ) : (
              draftRoutes.map((flow) => {
                const gateway = gatewayById.get(flow.gatewayId);
                const routeUrl = gateway ? liveUrl(gateway, flow.path) : null;
                return (
                  <div
                    key={flow.id}
                    role="link"
                    tabIndex={0}
                    onClick={() => router.push(`/flows/${flow.id}`)}
                    onKeyDown={(e) => e.key === "Enter" && router.push(`/flows/${flow.id}`)}
                    className="flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-border p-3 transition-colors hover:border-border-strong hover:bg-ink/3"
                  >
                    <span className="min-w-0">
                      <span className="block truncate text-sm font-medium text-foreground">{flow.name}</span>
                      <span className="block truncate font-mono text-xs text-subtle">
                        {flow.httpMethod}{" "}
                        {routeUrl ? <CopyableLink href={routeUrl}>{flow.path}</CopyableLink> : flow.path}
                      </span>
                    </span>
                    <StatusBadge status={flow.activeFlowVersionStatus ?? "DRAFT"} />
                  </div>
                );
              })
            )}
          </div>
        </Panel>
      </section>
    </div>
  );
}

function MetricCard({
  href,
  label,
  value,
  icon: Icon,
}: {
  href: string;
  label: string;
  value: number | null;
  icon: ComponentType<{ className?: string }>;
}) {
  return (
    <Link href={href} className="group flex flex-col rounded-xl border border-border bg-card p-4 transition-colors hover:bg-sun-soft sm:p-6">
      <span className="flex items-center justify-between text-sm text-muted-foreground">
        <span className="flex min-w-0 items-center gap-2">
          <Icon className="h-4 w-4 shrink-0 text-subtle" />
          <span className="truncate">{label}</span>
        </span>
        <span aria-hidden="true" className="text-subtle transition-transform group-hover:translate-x-0.5 group-hover:text-foreground">
          →
        </span>
      </span>
      {value === null ? (
        <Skeleton className="mt-5 h-10 w-14" />
      ) : (
        <span className="display mt-4 text-4xl text-foreground sm:text-[2.75rem]">{value}</span>
      )}
    </Link>
  );
}

function CompactMetric({
  href,
  label,
  value,
  icon: Icon,
}: {
  href: string;
  label: string;
  value: number | null;
  icon: ComponentType<{ className?: string }>;
}) {
  return (
    <Link href={href} className="flex items-center justify-between px-4 py-3 transition-colors hover:bg-ink/3">
      <span className="flex items-center gap-3">
        <Icon className="h-4 w-4 text-subtle" />
        <span className="text-sm text-foreground">{label}</span>
      </span>
      {value === null ? <Skeleton className="h-4 w-6" /> : <span className="font-mono text-sm text-muted-strong">{value}</span>}
    </Link>
  );
}
