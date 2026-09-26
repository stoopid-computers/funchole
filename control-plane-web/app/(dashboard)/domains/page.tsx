"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/Button";
import { CreatePanel } from "@/components/CreatePanel";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { PlusIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { DomainResponse, PaginationResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";

const PAGE_SIZE = 10;

export default function DomainsPage() {
  const [domains, setDomains] = useState<PaginationResponse<DomainResponse> | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [domainName, setDomainName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listDomains(page, PAGE_SIZE);
        if (!cancelled) setDomains(data);
      } catch {
        if (!cancelled) setError("Failed to load domains");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.createDomain({ domainName: domainName.trim() });
      setDomainName("");
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create domain");
    } finally {
      setBusy(false);
    }
  }

  async function handleVerify(domain: DomainResponse) {
    setError(null);
    try {
      await api.initiateDomainVerification(domain.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start verification");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Operate"
        title="Custom Domains"
        description="Register and verify the domains you want customers to use."
      />

      <CreatePanel title="Add domain" description="Use the bare domain only, for example example.com. Verification creates the TXT challenge.">
        <form onSubmit={handleCreate} className="flex flex-wrap items-end gap-3">
          <label className={`${fieldClass} min-w-56 flex-1`}>
            <span className={labelClass}>Domain name</span>
            <input
              type="text"
              required
              placeholder="example.com"
              value={domainName}
              onChange={(e) => setDomainName(e.target.value)}
              className={`${inputClass} font-mono`}
            />
          </label>
          <Button type="submit" variant="primary" disabled={busy}>
            <PlusIcon className="h-4 w-4" />
            Add domain
          </Button>
        </form>
      </CreatePanel>

      {error && (
        <FormError>
          {error}
        </FormError>
      )}

      <ResourceList title="Domain registry" description="Verified domains can be used for public entry points and certificates.">
        {!domains && <ResourceListState>Loading domains…</ResourceListState>}
        {domains?.items.length === 0 && <ResourceListState>No domains yet. Add one above to begin public URL setup.</ResourceListState>}
        {domains?.items.map((domain) => (
          <div key={domain.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-white/[0.03] lg:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <code className="font-mono text-base font-semibold text-foreground">{domain.domainName}</code>
                <StatusBadge status={domain.status} />
              </div>
              <p className="mt-2 text-xs text-muted-foreground">Verification TXT</p>
              <code className="mt-1 block break-all rounded-xl border border-border bg-surface-2 px-3 py-2 font-mono text-xs text-muted-strong">
                {domain.verificationCode ?? "No challenge generated yet"}
              </code>
            </div>
            <div className="flex items-center gap-2 lg:justify-end">
              {domain.status === "PENDING" && (
                <Button variant="secondary" size="sm" onClick={() => handleVerify(domain)}>
                  Verify DNS
                </Button>
              )}
            </div>
          </div>
        ))}
      </ResourceList>

      {domains && (
        <Pagination
          page={domains.page}
          totalPages={domains.totalPages}
          totalElements={domains.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
