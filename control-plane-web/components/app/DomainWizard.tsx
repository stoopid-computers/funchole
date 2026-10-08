"use client";

import { useState, type ReactNode } from "react";
import useSWR from "swr";
import { Button } from "@/components/Button";
import { confirmAction } from "@/components/ConfirmDialog";
import { FormError } from "@/components/FormError";
import { inputClass, labelClass } from "@/components/Input";
import { Chip } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { api } from "@/lib/api";
import { friendlyError } from "@/lib/errors";
import type { CustomDomainResponse } from "@/lib/types";

// Two labels (example.com) means the bare "apex" domain; anything longer is a
// subdomain. Imperfect for suffixes like .co.uk, so the wizard says what to do
// in plain words either way.
const isApex = (hostname: string) => hostname.split(".").length <= 2;

function DnsRecord({ type, name, value }: { type: string; name: string; value: string }) {
  const toast = useToast();
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast("Copied.");
    } catch {
      toast("Copy failed. Select the text instead.");
    }
  }
  const field = (label: string, text: string) => (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="text-xs font-semibold text-muted-foreground">{label}</span>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded-md border border-island-line bg-island px-2 py-1.5 font-mono text-xs text-island-code">{text}</code>
        <Button variant="secondary" size="sm" onClick={() => copy(text)} aria-label={`Copy ${label.toLowerCase()}`}>
          Copy
        </Button>
      </div>
    </div>
  );
  return (
    <div className="grid gap-3 rounded-xl border border-border bg-muted/60 p-4 sm:grid-cols-[5rem_1fr_1fr]">
      <div className="flex flex-col gap-1">
        <span className="text-xs font-semibold text-muted-foreground">Type</span>
        <span className="font-mono text-sm font-bold text-foreground">{type}</span>
      </div>
      {field("Name", name)}
      {field("Value", value)}
    </div>
  );
}

function Step({ n, title, state, children }: { n: number; title: string; state: "done" | "active" | "todo"; children?: ReactNode }) {
  return (
    <li className="flex gap-4">
      <span
        aria-hidden="true"
        className={`grid size-8 shrink-0 place-items-center rounded-full border-2 font-heading text-sm font-extrabold ${
          state === "done"
            ? "border-edge bg-live text-[var(--fh-on-sun)]"
            : state === "active"
              ? "border-edge bg-sun text-[var(--fh-on-sun)]"
              : "border-border-strong text-muted-foreground"
        }`}
      >
        {state === "done" ? "✓" : n}
      </span>
      <div className="min-w-0 flex-1">
        <h3 className={`font-heading text-lg font-extrabold tracking-tight ${state === "todo" ? "text-muted-foreground" : "text-foreground"}`}>{title}</h3>
        {state === "active" && children && <div className="mt-3 flex flex-col gap-3">{children}</div>}
      </div>
    </li>
  );
}

// Use your own domain, in three steps: name it, add the DNS records, check.
export function DomainWizard({ gatewayId }: { gatewayId: string }) {
  const toast = useToast();
  const domains = useSWR(["custom-domains", gatewayId], () => api.listGatewayCustomDomains(gatewayId), {
    refreshInterval: (data) => (data?.some((d) => d.status === "VERIFIED" && d.certStatus === "PENDING") ? 5000 : 0),
  });
  const [hostname, setHostname] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notReady, setNotReady] = useState(false);

  const domain: CustomDomainResponse | undefined = domains.data?.[0];

  async function add() {
    if (!hostname.trim()) return;
    setError(null);
    setBusy(true);
    try {
      await api.createGatewayCustomDomain(gatewayId, { hostname: hostname.trim().toLowerCase() });
      setHostname("");
      await domains.mutate();
    } catch (err) {
      setError(friendlyError(err, "We couldn't add that domain."));
    } finally {
      setBusy(false);
    }
  }

  async function check() {
    if (!domain) return;
    setError(null);
    setNotReady(false);
    setBusy(true);
    try {
      const result = await api.initiateCustomDomainVerification(domain.id);
      await domains.mutate();
      if (result.status !== "VERIFIED") setNotReady(true);
      else toast("Domain verified.");
    } catch (err) {
      setError(friendlyError(err, "We couldn't check your domain."));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!domain) return;
    const confirmed = await confirmAction({
      title: `Remove "${domain.hostname}"?`,
      description: "Your site stays available at its FuncHole address.",
      confirmLabel: "Remove",
    });
    if (!confirmed) return;
    setError(null);
    try {
      await api.deleteCustomDomain(domain.id);
      setNotReady(false);
      await domains.mutate();
    } catch (err) {
      setError(friendlyError(err, "We couldn't remove that domain."));
    }
  }

  const verified = domain?.status === "VERIFIED";
  const secure = verified && domain?.certStatus === "ACTIVE";

  return (
    <section aria-labelledby="own-domain" className="flex flex-col gap-5 rounded-2xl border border-border bg-card p-6">
      <div>
        <h2 id="own-domain" className="font-heading text-2xl font-extrabold tracking-tight text-foreground">
          Use your own domain
        </h2>
        <p className="mt-1 max-w-2xl text-[15px] text-muted-foreground">
          Show your site at a name like shop.yourbusiness.com instead of the FuncHole address. You need to be able to change your domain&apos;s DNS settings.
        </p>
      </div>

      {error && <FormError>{error}</FormError>}

      {domains.data === undefined ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : (
        <ol className="flex flex-col gap-6">
          <Step n={1} title="Choose your domain" state={domain ? "done" : "active"}>
            <div className="flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-2">
                <span className={labelClass}>Your domain name</span>
                <input
                  type="text"
                  value={hostname}
                  onChange={(event) => setHostname(event.target.value)}
                  placeholder="shop.yourbusiness.com"
                  className={`${inputClass} w-72 max-w-full`}
                />
              </label>
              <Button variant="primary" disabled={busy || !hostname.trim()} onClick={add}>
                {busy ? "Adding…" : "Continue"}
              </Button>
            </div>
          </Step>

          <Step n={2} title="Add two DNS records" state={!domain ? "todo" : verified ? "done" : "active"}>
            {domain && (
              <>
                <p className="text-[15px] text-muted-foreground">
                  Sign in where you bought <strong className="text-foreground">{domain.hostname}</strong> and add these records. This proves the domain is yours and points it at FuncHole.
                </p>
                <DnsRecord type="TXT" name={domain.verificationRecordName} value={domain.verificationCode ?? ""} />
                {isApex(domain.hostname) ? (
                  domain.gatewayPublicIp ? (
                    <DnsRecord type="A" name={domain.hostname} value={domain.gatewayPublicIp} />
                  ) : (
                    <p className="text-sm text-muted-foreground">Bare domains like {domain.hostname} aren&apos;t supported here yet. Try a subdomain such as www.{domain.hostname}.</p>
                  )
                ) : (
                  <DnsRecord type="CNAME" name={domain.hostname} value={domain.gatewayHostname} />
                )}
                <div className="flex flex-wrap items-center gap-3">
                  <Button variant="primary" disabled={busy} onClick={check}>
                    {busy ? "Checking…" : "I've added them, check now"}
                  </Button>
                  <Button variant="ghost" onClick={remove}>
                    Start over
                  </Button>
                </div>
                {notReady && (
                  <p role="status" className="text-sm text-muted-foreground">
                    Not ready yet. DNS changes can take a few minutes, sometimes longer. Try again soon.
                  </p>
                )}
              </>
            )}
          </Step>

          <Step n={3} title="We make it secure" state={secure ? "done" : verified ? "active" : "todo"}>
            {verified && domain && (
              <>
                {domain.certStatus === "FAILED" ? (
                  <>
                    <Chip tone="bad">Needs attention</Chip>
                    <p className="text-[15px] text-muted-foreground">
                      We couldn&apos;t secure {domain.hostname}. Check that the records above point to FuncHole. We keep trying in the background.
                    </p>
                  </>
                ) : (
                  <>
                    <Chip tone="pending">Securing your site…</Chip>
                    <p className="text-[15px] text-muted-foreground">This usually takes a few minutes. This page updates on its own.</p>
                  </>
                )}
              </>
            )}
          </Step>
        </ol>
      )}

      {secure && domain && (
        <div className="sticker flex flex-wrap items-center justify-between gap-3 rounded-xl bg-live-soft p-4" role="status">
          <div>
            <Chip tone="ok" pulse>
              Live
            </Chip>
            <p className="mt-2 font-heading text-lg font-extrabold break-all text-foreground">{domain.hostname}</p>
          </div>
          <div className="flex gap-2">
            <Button variant="primary" asChild>
              <a href={`https://${domain.hostname}`} target="_blank" rel="noreferrer">
                Open your site
              </a>
            </Button>
            <Button variant="ghost" onClick={remove}>
              Remove
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
