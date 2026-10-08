"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import useSWR from "swr";
import { DomainWizard } from "@/components/app/DomainWizard";
import { Button } from "@/components/Button";
import { CopyableLink } from "@/components/CopyableLink";
import { PageLoading } from "@/components/PageLoading";
import { StatusBadge } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { friendlyError } from "@/lib/errors";
import { useMode } from "@/lib/mode";
import { liveUrl } from "@/lib/urls";

// Simple-mode view of the gateway: your address, plus the own-domain wizard.
export function SimpleGatewayDetail() {
  const { gatewayId } = useParams<{ gatewayId: string }>();
  const { setMode } = useMode();
  const gateway = useSWR(["gateway", gatewayId], () => api.getGateway(gatewayId));

  if (!gateway.data) {
    return <PageLoading error={gateway.error ? friendlyError(gateway.error, "We couldn't load your live address.") : null} />;
  }

  const url = liveUrl(gateway.data);
  return (
    <div className="flex flex-col gap-8">
      <div>
        <Link href="/settings" className="text-sm font-semibold text-brand underline underline-offset-4">
          ← Settings
        </Link>
        <h1 className="display mt-3 text-3xl text-foreground sm:text-[2.5rem]">Your live address</h1>
      </div>

      <section className="sticker flex flex-col gap-3 rounded-2xl bg-card p-6">
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge status={gateway.data.status} />
          {gateway.data.certificate && <StatusBadge status={gateway.data.certificate.status} kind="certificate" />}
        </div>
        <p className="font-heading text-xl font-extrabold tracking-tight break-all text-foreground sm:text-2xl">{url.replace("https://", "")}</p>
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="primary" asChild>
            <a href={url} target="_blank" rel="noreferrer">
              Open
            </a>
          </Button>
          <CopyableLink href={url}>Copy link</CopyableLink>
        </div>
      </section>

      <DomainWizard gatewayId={gatewayId} />

      <p className="text-sm text-muted-foreground">
        Need the technical details?{" "}
        <button type="button" className="cursor-pointer font-semibold text-brand underline underline-offset-4" onClick={() => setMode("advanced")}>
          Switch to Advanced
        </button>
      </p>
    </div>
  );
}
