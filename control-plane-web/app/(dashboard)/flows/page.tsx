"use client";

import Link from "next/link";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import useSWR from "swr";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { Button, buttonClasses } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { PlusIcon, TrashIcon, ServerIcon } from "@/components/icons";
import { EmptyState } from "@/components/EmptyState";
import { CopyableLink } from "@/components/CopyableLink";
import { api } from "@/lib/api";
import type { FlowResponse, GatewayResponse, PaginationResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { Modal } from "@/components/Modal";
import { confirmAction } from "@/components/ConfirmDialog";
import { NativeSelect } from "@/components/ui/native-select";
import { friendlyError } from "@/lib/errors";
import { gatewayHost, liveUrl } from "@/lib/urls";
import { timeAgo } from "@/lib/time";
import { useMode, usePageCopy } from "@/lib/mode";
import { AskAgentEmpty } from "@/components/AskAgentEmpty";

const PAGE_SIZE = 10;
const HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

interface FlowFormState {
  flowKey: string;
  name: string;
  description: string;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority: string;
}

const EMPTY_FORM: FlowFormState = {
  flowKey: "",
  name: "",
  description: "",
  gatewayId: "",
  httpMethod: "GET",
  path: "/",
  priority: "100",
};

export default function FlowsPage() {
  const { mode, noun } = useMode();
  const copy = usePageCopy("flows");
  const [flows, setFlows] = useState<PaginationResponse<FlowResponse> | null>(null);
  const [gateways, setGateways] = useState<GatewayResponse[]>([]);
  // False until the entry point list has loaded, so the "create one first" guidance doesn't flash.
  const [gatewaysLoaded, setGatewaysLoaded] = useState(false);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<FlowFormState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listFlows(page, PAGE_SIZE);
        if (!cancelled) setFlows(data);
      } catch (err) {
        if (!cancelled) setError(friendlyError(err, "Failed to load flows"));
      }
      try {
        const gatewayData = await api.listGateways(1, 100);
        if (!cancelled) setGateways(gatewayData.items);
      } catch {
        if (!cancelled) setGateways([]);
      }
      if (!cancelled) setGatewaysLoaded(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  // Only Simple mode shows visit counts; it costs one extra request.
  const { data: activity } = useSWR(mode === "simple" ? "activity-summary" : null, () => api.getActivitySummary());
  const activityByFlow = useMemo(() => new Map((activity?.pages ?? []).map((p) => [p.flowId, p])), [activity]);

  const gatewayById = useMemo(() => new Map(gateways.map((gateway) => [gateway.id, gateway])), [gateways]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openCreate() {
    setError(null);
    setForm({ ...EMPTY_FORM, gatewayId: gateways[0]?.id ?? "" });
  }

  function closeForm() {
    if (busy) return;
    setError(null);
    setForm(null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form) return;
    setError(null);
    setBusy(true);
    try {
      await api.createFlow({
        flowKey: form.flowKey.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        gatewayId: form.gatewayId,
        httpMethod: form.httpMethod,
        path: form.path.trim(),
        priority: form.priority.trim() ? Number(form.priority) : null,
      });
      closeForm();
      refresh();
    } catch (err) {
      setError(friendlyError(err, "Failed to create flow"));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(flow: FlowResponse) {
    if (!await confirmAction({ title: `Delete ${noun("flow")} "${flow.name}"?`, confirmLabel: "Delete" })) {
      return;
    }
    setError(null);
    try {
      await api.deleteFlow(flow.id);
      refresh();
    } catch (err) {
      setError(friendlyError(err, "Failed to delete flow"));
    }
  }

  const needsGateway = gatewaysLoaded && gateways.length === 0;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow={copy.eyebrow}
        title={copy.title}
        description={copy.description}
        actions={
        copy.create === null ? undefined : (
        <Button variant="primary" onClick={openCreate} disabled={gateways.length === 0}>
          <PlusIcon className="h-4 w-4" />
          {copy.create}
        </Button>
        )
        }
      />

      {/* When the list is empty its empty state carries this guidance instead. */}
      {needsGateway && (flows?.items.length ?? 0) > 0 && (
        <p className="rounded-xl border border-warning/25 bg-warning/[0.06] px-4 py-3 text-sm text-warning">
          You need at least one entry point before creating a workflow.{" "}
          <Link href="/gateways" className="font-medium underline underline-offset-4 hover:text-foreground">
            Create an entry point
          </Link>
        </p>
      )}

      {form && (
        <Modal
          title="New workflow"
          description="Choose the public method and path this workflow should answer."
          onClose={closeForm}
          dismissible={!busy}
          widthClassName="max-w-2xl"
          footer={
            <>
              <Button type="button" variant="secondary" onClick={closeForm} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" form="flow-form" variant="primary" disabled={busy}>
                Create workflow
              </Button>
            </>
          }
        >
          <form id="flow-form" onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
            <label className={fieldClass}>
              <span className={labelClass}>Workflow key</span>
              <input
                type="text"
                required
                maxLength={150}
                placeholder="flw_orders_list"
                pattern="[a-zA-Z0-9_.\-]+"
                value={form.flowKey}
                onChange={(e) => setForm({ ...form, flowKey: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Name</span>
              <input
                type="text"
                required
                maxLength={255}
                placeholder="Orders List"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={`${fieldClass} sm:col-span-2`}>
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
              <span className={labelClass}>Entry point</span>
              <NativeSelect
                required
                value={form.gatewayId}
                onChange={(e) => setForm({ ...form, gatewayId: e.target.value })}
                className="w-full"
              >
                {gateways.map((gateway) => (
                  <option key={gateway.id} value={gateway.id}>
                    {gateway.name} ({gatewayHost(gateway)})
                  </option>
                ))}
              </NativeSelect>
            </label>
            <div className="grid grid-cols-3 gap-3">
              <label className={fieldClass}>
                <span className={labelClass}>Method</span>
                <NativeSelect
                  value={form.httpMethod}
                  onChange={(e) => setForm({ ...form, httpMethod: e.target.value })}
                  className="w-full"
                >
                  {HTTP_METHODS.map((method) => (
                    <option key={method} value={method}>
                      {method}
                    </option>
                  ))}
                </NativeSelect>
              </label>
              <label className={`${fieldClass} col-span-2`}>
                <span className={labelClass}>Path</span>
                <input
                  type="text"
                  required
                  placeholder="/orders"
                  value={form.path}
                  onChange={(e) => setForm({ ...form, path: e.target.value })}
                  className={`${inputClass} font-mono`}
                />
              </label>
            </div>
            <label className={fieldClass}>
              <span className={labelClass}>Priority</span>
              <input
                type="number"
                value={form.priority}
                onChange={(e) => setForm({ ...form, priority: e.target.value })}
                className={inputClass}
              />
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

      <ResourceList title={copy.listTitle} description={copy.listDescription}>
        {!flows && <ResourceListState>{copy.loading}</ResourceListState>}
        {flows?.items.length === 0 && mode === "simple" && (
          <AskAgentEmpty title={copy.emptyTitle} description={copy.emptyDescription} />
        )}
        {flows?.items.length === 0 && gatewaysLoaded && mode === "advanced" && (
          needsGateway ? (
            <EmptyState
              title="Create an entry point first"
              description="A workflow answers requests on an entry point's public host. Create one, then come back to add a workflow."
              action={
                <Button variant="primary" size="sm" asChild>
                  <Link href="/gateways">
                    <ServerIcon className="h-4 w-4" />
                    Create an entry point
                  </Link>
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No workflows yet"
              description="Choose a public method and path on one of your entry points, then connect it to actions."
              action={
                <Button variant="primary" size="sm" onClick={openCreate}>
                  <PlusIcon className="h-4 w-4" />
                  New workflow
                </Button>
              }
            />
          )
        )}
        {flows?.items.map((flow) => {
          const flowGateway = gatewayById.get(flow.gatewayId);
          const routeUrl = flowGateway ? liveUrl(flowGateway, flow.path) : null;
          const visits = activityByFlow.get(flow.id);
          return (
          <div key={flow.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-ink/4 xl:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                {mode === "advanced" && (
                  <span className="rounded-md bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">{flow.httpMethod}</span>
                )}
                <code className="truncate font-mono text-sm text-foreground">
                  {routeUrl ? <CopyableLink href={routeUrl}>{flow.path}</CopyableLink> : flow.path}
                </code>
                {flow.activeFlowVersionStatus ? <StatusBadge status={flow.activeFlowVersionStatus} /> : <StatusBadge status="DRAFT" />}
              </div>
              <Link href={`/flows/${flow.id}`} className="mt-3 block text-base font-semibold text-foreground hover:text-muted-strong">
                {flow.name}
              </Link>
              {mode === "simple" && (
                <p className="mt-1 text-sm text-muted-foreground">
                  {visits
                    ? `${visits.requests24h} visit${visits.requests24h === 1 ? "" : "s"} today${visits.lastRequestAt ? ` · last ${timeAgo(visits.lastRequestAt)}` : ""}`
                    : activity ? "No visits today" : ""}
                </p>
              )}
              {mode === "advanced" && (
                <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <code className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 font-mono">{flow.flowKey}</code>
                  <span>Entry point: {flow.gatewayName}</span>
                  <span>Priority: {flow.priority}</span>
                </div>
              )}
            </div>
            <div className="flex items-center gap-2 xl:justify-end">
              <Link href={`/flows/${flow.id}`} className={buttonClasses("secondary", "sm")}>
                {copy.open}
              </Link>
              <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(flow)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </div>
          </div>
          );
        })}
      </ResourceList>

      {mode === "simple" && (
        <p className="text-sm text-muted-foreground">
          Curious how it works?{" "}
          <Link href="/functions" className="font-semibold text-brand underline underline-offset-4">
            See the features behind your pages
          </Link>
        </p>
      )}

      {flows && (
        <Pagination
          page={flows.page}
          totalPages={flows.totalPages}
          totalElements={flows.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
