"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { Panel } from "@/components/Panel";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { CopyableCommand } from "@/components/CopyableCommand";
import { CopyableLink } from "@/components/CopyableLink";
import { ArrowLeftIcon, ChevronRightIcon, PlusIcon, TrashIcon, GlobeIcon } from "@/components/icons";
import { api } from "@/lib/api";
import type { GatewayResponse, CustomDomainResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { confirmAction } from "@/components/ConfirmDialog";
import { friendlyError } from "@/lib/errors";
import { PageLoading } from "@/components/PageLoading";
import { gatewayHost, liveUrl } from "@/lib/urls";
import { useMode } from "@/lib/mode";
import { SimpleGatewayDetail } from "@/components/app/SimpleGatewayDetail";

export default function GatewayDetailPage() {
  const { mode } = useMode();
  return mode === "simple" ? <SimpleGatewayDetail /> : <AdvancedGatewayDetail />;
}

function AdvancedGatewayDetail() {
  const params = useParams<{ gatewayId: string }>();
  const gatewayId = params.gatewayId;

  const [gateway, setGateway] = useState<GatewayResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.getGateway(gatewayId);
        if (!cancelled) setGateway(data);
      } catch (err) {
        if (!cancelled) setError(friendlyError(err, "Failed to load entry point"));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [gatewayId]);

  if (!gateway) {
    return <PageLoading error={error} />;
  }

  return (
    <div className="flex flex-col gap-6">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link href="/gateways" className="hover:text-foreground">
          Entry Points
        </Link>
        <ChevronRightIcon className="h-3.5 w-3.5" />
        <span className="text-foreground">{gateway.name}</span>
      </nav>

      <div className="flex items-start gap-3">
        <Link
          href="/gateways"
          aria-label="Back to entry points"
          className="mt-1 flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground"
        >
          <ArrowLeftIcon className="h-4 w-4" />
        </Link>
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-medium tracking-tight text-foreground">{gateway.name}</h1>
            <StatusBadge status={gateway.status} />
            {gateway.certificate ? (
              <StatusBadge status={gateway.certificate.status} kind="certificate" />
            ) : (
              <span className="rounded-md border border-border px-1.5 py-0.5 text-xs text-muted-foreground">No certificate</span>
            )}
          </div>
          <code className="mt-2 block font-mono text-sm text-foreground">
            <CopyableLink href={liveUrl(gateway)}>
              {gatewayHost(gateway)}
            </CopyableLink>
          </code>
          {gateway.description && <p className="mt-1 text-sm text-muted-foreground">{gateway.description}</p>}
        </div>
      </div>

      {error && (
        <FormError>
          {error}
        </FormError>
      )}

      <CustomDomainsPanel gatewayId={gatewayId} gatewayHostname={gatewayHost(gateway)} onError={setError} />
    </div>
  );
}

interface CustomDomainsPanelProps {
  gatewayId: string;
  gatewayHostname: string;
  onError: (message: string) => void;
}

function CustomDomainsPanel({ gatewayId, gatewayHostname, onError }: CustomDomainsPanelProps) {
  const [domains, setDomains] = useState<CustomDomainResponse[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [hostname, setHostname] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listGatewayCustomDomains(gatewayId);
        if (!cancelled) setDomains(data);
      } catch (err) {
        if (!cancelled) onError(friendlyError(err, "Failed to load custom domains"));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [gatewayId, reloadKey, onError]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  async function handleAttach() {
    if (!hostname.trim()) return;
    onError("");
    setBusy(true);
    try {
      await api.createGatewayCustomDomain(gatewayId, { hostname: hostname.trim() });
      setHostname("");
      setAdding(false);
      refresh();
    } catch (err) {
      onError(friendlyError(err, "Failed to attach domain"));
    } finally {
      setBusy(false);
    }
  }

  async function handleVerify(domain: CustomDomainResponse) {
    onError("");
    try {
      await api.initiateCustomDomainVerification(domain.id);
      refresh();
    } catch (err) {
      onError(friendlyError(err, "Failed to start verification"));
    }
  }

  async function handleDetach(domain: CustomDomainResponse) {
    if (!await confirmAction({ title: `Detach "${domain.hostname}" from this entry point?`, confirmLabel: "Detach" })) {
      return;
    }
    onError("");
    try {
      await api.deleteCustomDomain(domain.id);
      refresh();
    } catch (err) {
      onError(friendlyError(err, "Failed to detach domain"));
    }
  }

  return (
    <Panel className="flex flex-col gap-5 p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <GlobeIcon className="h-4 w-4 text-subtle" />
            <p className="eyebrow">Bring your own hostname</p>
          </div>
          <h2 className="mt-2 text-base font-medium tracking-tight text-foreground">Custom domain</h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            Point a domain you own at this entry point - a subdomain via CNAME, or an apex domain (e.g.{" "}
            <code className="font-mono">example.com</code>) via A record. Verify DNS ownership, then traffic to it
            routes exactly like <code className="font-mono">{gatewayHostname}</code>.
          </p>
        </div>
        {!adding && (
          <Button variant="secondary" size="sm" onClick={() => setAdding(true)}>
            <PlusIcon className="h-3.5 w-3.5" />
            Attach domain
          </Button>
        )}
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-surface-2/45">
        {domains === null ? (
          <p className="px-4 py-4 text-sm text-muted-foreground">Loading…</p>
        ) : domains.length === 0 && !adding ? (
          <div className="px-4 py-5">
            <p className="text-sm font-medium text-foreground">No custom domain attached.</p>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">Attach one above to serve this entry point on your own domain.</p>
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {domains?.map((domain) => (
              <li key={domain.id} className="flex flex-col gap-3 px-4 py-4">
                <div className="flex flex-wrap items-center gap-2">
                  <code className="font-mono text-sm font-semibold text-foreground">
                    <CopyableLink href={`https://${domain.hostname}`}>{domain.hostname}</CopyableLink>
                  </code>
                  <StatusBadge status={domain.status} />
                  <StatusBadge status={domain.certStatus} kind="certificate" />
                  <Button
                    variant="danger"
                    size="icon"
                    title="Detach"
                    className="ml-auto"
                    onClick={() => handleDetach(domain)}
                  >
                    <TrashIcon className="h-3.5 w-3.5" />
                  </Button>
                </div>

                {domain.status === "PENDING" && (
                  <div className="flex flex-col gap-2">
                    <p className="text-xs text-muted-foreground">
                      Add a DNS TXT record at{" "}
                      <code className="font-mono text-muted-strong">
                        {domain.verificationRecordName}
                      </code>{" "}
                      with this value, then verify:
                    </p>
                    <CopyableCommand value={domain.verificationCode ?? "No challenge generated yet"} />
                    <p className="text-xs text-muted-foreground">
                      Subdomain? Point it with a <code className="font-mono">CNAME</code> to{" "}
                      <code className="font-mono text-muted-strong">
                        <CopyableLink href={`https://${domain.gatewayHostname}`}>{domain.gatewayHostname}</CopyableLink>
                      </code>.{" "}
                      {domain.gatewayPublicIp
                        ? (
                          <>
                            Apex domain? Use an <code className="font-mono">A</code> record to{" "}
                            <code className="font-mono text-muted-strong">{domain.gatewayPublicIp}</code>.
                          </>
                        )
                        : "Apex domains aren't supported on this deployment yet."}
                    </p>
                    <Button variant="secondary" size="sm" className="self-start" onClick={() => handleVerify(domain)}>
                      Verify DNS
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        {adding && (
          <div className="flex flex-wrap items-end gap-2 border-t border-border px-4 py-3">
            <label className={fieldClass}>
              <span className={labelClass}>Hostname</span>
              <input
                type="text"
                value={hostname}
                onChange={(e) => setHostname(e.target.value)}
                placeholder="hello.example.com"
                className={`${inputClass} w-56 font-mono text-xs`}
              />
            </label>
            <Button variant="primary" size="sm" disabled={busy || !hostname.trim()} onClick={handleAttach}>
              Attach
            </Button>
            <Button variant="secondary" size="sm" onClick={() => setAdding(false)}>
              Cancel
            </Button>
          </div>
        )}
      </div>
    </Panel>
  );
}
