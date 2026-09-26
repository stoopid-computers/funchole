"use client";

import { FormError } from "@/components/FormError";
import { Modal } from "@/components/Modal";
import { NativeSelect } from "@/components/ui/native-select";
import { confirmAction } from "@/components/ConfirmDialog";
import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { PlusIcon, PencilIcon, TrashIcon, GlobeIcon } from "@/components/icons";
import { EmptyState } from "@/components/EmptyState";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import type {
  DomainResponse,
  GatewayResponse,
  GatewayStatus,
  PaginationResponse,
} from "@/lib/types";

const PAGE_SIZE = 10;

interface GatewayFormState {
  name: string;
  description: string;
  appDomainId: string;
  status: GatewayStatus;
}

const EMPTY_FORM: GatewayFormState = {
  name: "",
  description: "",
  appDomainId: "",
  status: "ACTIVE",
};

export default function GatewaysPage() {
  const [gateways, setGateways] = useState<PaginationResponse<GatewayResponse> | null>(null);
  const [domains, setDomains] = useState<DomainResponse[]>([]);
  // null until the domain list has loaded, so the "add a domain" guidance doesn't flash.
  const [unverifiedDomainCount, setUnverifiedDomainCount] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<GatewayFormState | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listGateways(page, PAGE_SIZE);
        if (!cancelled) setGateways(data);
      } catch {
        if (!cancelled) setError("Failed to load gateways");
      }
      try {
        const domainData = await api.listDomains(1, 100);
        if (!cancelled) {
          setDomains(domainData.items.filter((d) => d.status === "VERIFIED"));
          setUnverifiedDomainCount(domainData.items.filter((d) => d.status !== "VERIFIED").length);
        }
      } catch {
        if (!cancelled) {
          setDomains([]);
          setUnverifiedDomainCount(0);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openCreate() {
    setError(null);
    setEditingId(null);
    setForm({ ...EMPTY_FORM, appDomainId: domains[0]?.id ?? "" });
  }

  function openEdit(gateway: GatewayResponse) {
    setError(null);
    setEditingId(gateway.id);
    setForm({
      name: gateway.name,
      description: gateway.description ?? "",
      appDomainId: gateway.appDomainId,
      status: gateway.status,
    });
  }

  function closeForm() {
    if (busy) return;
    setError(null);
    setForm(null);
    setEditingId(null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form) return;
    setError(null);
    setBusy(true);
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      appDomainId: form.appDomainId,
      status: form.status,
    };
    try {
      if (editingId) {
        await api.updateGateway(editingId, payload);
      } else {
        await api.createGateway(payload);
      }
      closeForm();
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save gateway");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(gateway: GatewayResponse) {
    if (!await confirmAction(`Delete gateway "${gateway.name}"?`)) {
      return;
    }
    setError(null);
    try {
      await api.deleteGateway(gateway.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete gateway");
    }
  }

  const needsDomain = unverifiedDomainCount !== null && domains.length === 0;
  const domainAction = unverifiedDomainCount ? "Verify your domain" : "Add a custom domain";

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Operate"
        title="Entry Points"
        description="Public hosts with certificates. An entry point becomes the stable hostname for customer-facing workflows."
        actions={
        <Button variant="primary" onClick={openCreate} disabled={domains.length === 0}>
          <PlusIcon className="h-4 w-4" />
          New entry point
        </Button>
        }
      />

      {/* When the list is empty its empty state carries this guidance instead. */}
      {needsDomain && (gateways?.items.length ?? 0) > 0 && (
        <p className="rounded-xl border border-warning/25 bg-warning/[0.06] px-4 py-3 text-sm text-warning">
          You need at least one verified domain before creating an entry point.{" "}
          <Link href="/domains" className="font-medium underline underline-offset-4 hover:text-foreground">
            {domainAction}
          </Link>
        </p>
      )}

      {form && (
        <Modal
          title={editingId ? "Edit entry point" : "New entry point"}
          description="Choose a verified domain. FuncHole generates the unique host key and certificate metadata."
          onClose={closeForm}
          dismissible={!busy}
          widthClassName="max-w-2xl"
          footer={
            <>
              <Button type="button" variant="secondary" onClick={closeForm} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" form="gateway-form" variant="primary" disabled={busy}>
                {editingId ? "Save changes" : "Create entry point"}
              </Button>
            </>
          }
        >
          <form id="gateway-form" onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
            <label className={fieldClass}>
              <span className={labelClass}>Name</span>
              <input
                type="text"
                required
                maxLength={100}
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Description</span>
              <input
                type="text"
                maxLength={1000}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Domain</span>
              <NativeSelect
                required
                value={form.appDomainId}
                onChange={(e) => setForm({ ...form, appDomainId: e.target.value })}
                className="w-full"
              >
                {domains.map((domain) => (
                  <option key={domain.id} value={domain.id}>
                    {domain.domainName}
                  </option>
                ))}
              </NativeSelect>
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Status</span>
              <NativeSelect
                value={form.status}
                onChange={(e) => setForm({ ...form, status: e.target.value as GatewayStatus })}
                className="w-full"
              >
                <option value="ACTIVE">ACTIVE</option>
                <option value="INACTIVE">INACTIVE</option>
              </NativeSelect>
            </label>
            {error && (
              <div className="sm:col-span-2">
                <FormError>{error}</FormError>
              </div>
            )}
          </form>
        </Modal>
      )}

      {error && !form && (
        <FormError>
          {error}
        </FormError>
      )}

      <ResourceList title="Entry point registry" description="Hosts available for live workflows and certificate-backed traffic.">
        {!gateways && <ResourceListState>Loading entry points…</ResourceListState>}
        {gateways?.items.length === 0 && unverifiedDomainCount !== null && (
          needsDomain ? (
            <EmptyState
              title={unverifiedDomainCount > 0 ? "Verify your custom domain first" : "Add a custom domain first"}
              description={
                unverifiedDomainCount > 0
                  ? "Entry points run on a verified domain. Finish verifying your domain's TXT record, then come back to create one."
                  : "Entry points run on a verified domain. Add one and verify it with a TXT record, then come back to create one."
              }
              action={
                <Button variant="primary" size="sm" asChild>
                  <Link href="/domains">
                    <GlobeIcon className="h-4 w-4" />
                    {domainAction}
                  </Link>
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No entry points yet"
              description="Create a public host on one of your verified domains, then route workflows through it."
              action={
                <Button variant="primary" size="sm" onClick={openCreate}>
                  <PlusIcon className="h-4 w-4" />
                  New entry point
                </Button>
              }
            />
          )
        )}
        {gateways?.items.map((gateway) => (
          <div key={gateway.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-white/[0.03] lg:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-base font-semibold text-foreground">{gateway.name}</p>
                <StatusBadge status={gateway.status} />
                {gateway.certificate ? <StatusBadge status={gateway.certificate.status} /> : <span className="rounded-md border border-border px-1.5 py-0.5 text-xs text-muted-foreground">No certificate</span>}
              </div>
              <code className="mt-3 block truncate font-mono text-sm text-foreground">
                {gateway.uniqueKey}.{gateway.domainName}
              </code>
              {gateway.description && <p className="mt-2 text-sm leading-6 text-muted-foreground">{gateway.description}</p>}
            </div>
            <div className="flex items-center gap-2 lg:justify-end">
              <Button variant="secondary" size="sm" onClick={() => openEdit(gateway)}>
                <PencilIcon className="h-3.5 w-3.5" />
                Edit
              </Button>
              <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(gateway)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </div>
          </div>
        ))}
      </ResourceList>

      {gateways && (
        <Pagination
          page={gateways.page}
          totalPages={gateways.totalPages}
          totalElements={gateways.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
