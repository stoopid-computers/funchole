"use client";

import { FormError } from "@/components/FormError";
import { NativeSelect } from "@/components/ui/native-select";
import { confirmAction } from "@/components/ConfirmDialog";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { panelClass, Panel } from "@/components/Panel";
import { Button, buttonClasses } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { ArrowLeftIcon, ChevronRightIcon, PlusIcon, PencilIcon, TrashIcon, PlayIcon, ArchiveIcon, KeyIcon, DatabaseIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type {
  DatabaseResponse,
  EnvironmentProfileResponse,
  FlowDatabaseAttachmentResponse,
  FlowEnvironmentAttachmentResponse,
  FlowResponse,
  FlowVersionResponse,
  GatewayResponse,
} from "@/lib/types";

const HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

interface EditFormState {
  name: string;
  description: string;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority: string;
}

export default function FlowDetailPage() {
  const params = useParams<{ flowId: string }>();
  const router = useRouter();
  const flowId = params.flowId;

  const [flow, setFlow] = useState<FlowResponse | null>(null);
  const [versions, setVersions] = useState<FlowVersionResponse[]>([]);
  const [gateways, setGateways] = useState<GatewayResponse[]>([]);
  const [environments, setEnvironments] = useState<EnvironmentProfileResponse[]>([]);
  const [databases, setDatabases] = useState<DatabaseResponse[]>([]);
  const [flowEnvironments, setFlowEnvironments] = useState<FlowEnvironmentAttachmentResponse[]>([]);
  const [flowDatabases, setFlowDatabases] = useState<FlowDatabaseAttachmentResponse[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [editForm, setEditForm] = useState<EditFormState | null>(null);
  const [environmentToAttach, setEnvironmentToAttach] = useState("");
  const [databaseToAttach, setDatabaseToAttach] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.getFlow(flowId);
        if (!cancelled) setFlow(data);
      } catch {
        if (!cancelled) setError("Failed to load flow");
      }
      try {
        const versionData = await api.listFlowVersions(flowId, 1, 50);
        if (!cancelled) setVersions(versionData.items);
      } catch {
        if (!cancelled) setVersions([]);
      }
      try {
        const gatewayData = await api.listGateways(1, 100);
        if (!cancelled) setGateways(gatewayData.items);
      } catch {
        if (!cancelled) setGateways([]);
      }
      try {
        const environmentData = await api.listEnvironments(1, 100);
        if (!cancelled) setEnvironments(environmentData.items);
      } catch {
        if (!cancelled) setEnvironments([]);
      }
      try {
        const databaseData = await api.listDatabases(1, 100);
        if (!cancelled) setDatabases(databaseData.items);
      } catch {
        if (!cancelled) setDatabases([]);
      }
      try {
        const data = await api.listFlowEnvironments(flowId);
        if (!cancelled) setFlowEnvironments(data);
      } catch {
        if (!cancelled) setFlowEnvironments([]);
      }
      try {
        const data = await api.listFlowDatabases(flowId);
        if (!cancelled) setFlowDatabases(data);
      } catch {
        if (!cancelled) setFlowDatabases([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [flowId, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openEdit() {
    if (!flow) return;
    setEditForm({
      name: flow.name,
      description: flow.description ?? "",
      gatewayId: flow.gatewayId,
      httpMethod: flow.httpMethod,
      path: flow.path,
      priority: String(flow.priority),
    });
  }

  async function handleEditSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editForm) return;
    setError(null);
    setBusy(true);
    try {
      await api.updateFlow(flowId, {
        name: editForm.name.trim(),
        description: editForm.description.trim() || null,
        gatewayId: editForm.gatewayId,
        httpMethod: editForm.httpMethod,
        path: editForm.path.trim(),
        priority: editForm.priority.trim() ? Number(editForm.priority) : null,
      });
      setEditForm(null);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to update flow");
    } finally {
      setBusy(false);
    }
  }

  async function handleDeleteFlow() {
    if (!flow || !await confirmAction(`Delete flow "${flow.name}"? This cannot be undone.`)) return;
    setError(null);
    try {
      await api.deleteFlow(flowId);
      router.replace("/flows");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete flow");
    }
  }

  async function handleNewDraft() {
    setError(null);
    setBusy(true);
    try {
      const version = await api.createFlowVersion(flowId, { runtime: "NODE" });
      router.push(`/flows/${flowId}/versions/${version.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create draft version");
      setBusy(false);
    }
  }

  async function handleAdopt(version: FlowVersionResponse) {
    setError(null);
    try {
      await api.adoptFlowVersion(flowId, version.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to adopt version");
    }
  }

  async function handleArchive(version: FlowVersionResponse) {
    setError(null);
    try {
      await api.archiveFlowVersion(flowId, version.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to archive version");
    }
  }

  async function handleDeleteVersion(version: FlowVersionResponse) {
    if (!await confirmAction(`Delete draft v${version.version}?`)) return;
    setError(null);
    try {
      await api.deleteFlowVersion(flowId, version.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete version");
    }
  }

  async function handleAttachEnvironment() {
    if (!environmentToAttach) return;
    setError(null);
    try {
      const data = await api.attachFlowEnvironment(flowId, environmentToAttach, 100);
      setFlowEnvironments(data);
      setEnvironmentToAttach("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to attach environment");
    }
  }

  async function handleDetachEnvironment(environmentId: string) {
    setError(null);
    try {
      const data = await api.detachFlowEnvironment(flowId, environmentId);
      setFlowEnvironments(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to detach environment");
    }
  }

  async function handleAttachDatabase() {
    if (!databaseToAttach) return;
    setError(null);
    try {
      const data = await api.attachFlowDatabase(flowId, databaseToAttach);
      setFlowDatabases(data);
      setDatabaseToAttach("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to attach database");
    }
  }

  async function handleDetachDatabase(databaseId: string) {
    setError(null);
    try {
      const data = await api.detachFlowDatabase(flowId, databaseId);
      setFlowDatabases(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to detach database");
    }
  }

  if (!flow) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }

  const sortedVersions = [...versions].sort((a, b) => b.version - a.version);

  return (
    <div className="flex flex-col gap-6">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link href="/flows" className="hover:text-foreground">
          Flows
        </Link>
        <ChevronRightIcon className="h-3.5 w-3.5" />
        <span className="text-foreground">{flow.name}</span>
      </nav>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <Link
            href="/flows"
            className="mt-1 flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground"
          >
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
          <div>
            <h1 className="text-2xl font-medium tracking-tight text-foreground">{flow.name}</h1>
            <p className="mt-1 flex items-center gap-2 font-mono text-xs text-muted-foreground">
              <span className="text-muted-strong">{flow.httpMethod}</span>
              {flow.path}
              <span className="text-border-strong">&middot;</span>
              {flow.flowKey}
            </p>
            {flow.description && <p className="mt-1 text-sm text-muted-foreground">{flow.description}</p>}
          </div>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button variant="secondary" onClick={openEdit}>
            <PencilIcon className="h-4 w-4" />
            Edit
          </Button>
          <Button variant="danger" onClick={handleDeleteFlow}>
            <TrashIcon className="h-4 w-4" />
            Delete
          </Button>
        </div>
      </div>

      {editForm && (
        <form onSubmit={handleEditSubmit} className={`${panelClass} grid gap-4 p-4 sm:grid-cols-2`}>
          <label className={fieldClass}>
            <span className={labelClass}>Name</span>
            <input
              type="text"
              required
              maxLength={255}
              value={editForm.name}
              onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className={fieldClass}>
            <span className={labelClass}>Gateway</span>
            <NativeSelect
              required
              value={editForm.gatewayId}
              onChange={(e) => setEditForm({ ...editForm, gatewayId: e.target.value })}
              className="w-full"
            >
              {gateways.map((gateway) => (
                <option key={gateway.id} value={gateway.id}>
                  {gateway.name} ({gateway.uniqueKey}.{gateway.domainName})
                </option>
              ))}
            </NativeSelect>
          </label>
          <label className={`${fieldClass} sm:col-span-2`}>
            <span className={labelClass}>Description</span>
            <input
              type="text"
              maxLength={1000}
              value={editForm.description}
              onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
              className={inputClass}
            />
          </label>
          <div className="grid grid-cols-3 gap-3">
            <label className={fieldClass}>
              <span className={labelClass}>Method</span>
              <NativeSelect
                value={editForm.httpMethod}
                onChange={(e) => setEditForm({ ...editForm, httpMethod: e.target.value })}
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
                value={editForm.path}
                onChange={(e) => setEditForm({ ...editForm, path: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </label>
          </div>
          <label className={fieldClass}>
            <span className={labelClass}>Priority</span>
            <input
              type="number"
              value={editForm.priority}
              onChange={(e) => setEditForm({ ...editForm, priority: e.target.value })}
              className={inputClass}
            />
          </label>
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" variant="primary" disabled={busy}>
              Save changes
            </Button>
            <Button type="button" variant="secondary" onClick={() => setEditForm(null)}>
              Cancel
            </Button>
          </div>
        </form>
      )}

      {error && (
        <FormError>
          {error}
        </FormError>
      )}

      <Panel className="p-4">
        <p className="text-sm font-medium text-foreground">Active version</p>
        {flow.activeFlowVersionId && flow.activeFlowVersionStatus ? (
          <Link
            href={`/flows/${flowId}/versions/${flow.activeFlowVersionId}`}
            className="mt-2 flex items-center gap-2 text-sm text-foreground underline-offset-4 hover:underline"
          >
            <StatusBadge status={flow.activeFlowVersionStatus} />
            View adopted version
            <ChevronRightIcon className="h-3.5 w-3.5" />
          </Link>
        ) : (
          <p className="mt-2 text-sm text-muted-foreground">No version is adopted yet. This route will not resolve on the gateway.</p>
        )}
      </Panel>

      <div className="grid gap-4 lg:grid-cols-2">
        <FlowAttachmentCard
          icon={<KeyIcon className="h-4 w-4 text-subtle" />}
          title="Inherited environments"
          description="Variables and secrets attached here are available to every function step in this Flow."
          emptyText="No environments attached."
          selectValue={environmentToAttach}
          selectPlaceholder="Select environment"
          options={environments.map((environment) => ({
            id: environment.id,
            label: `${environment.name} (${environment.environmentKey})`,
          }))}
          items={flowEnvironments.map((attachment) => ({
            id: attachment.environmentProfileId,
            title: attachment.environmentName,
            subtitle: attachment.environmentKey,
          }))}
          onSelect={setEnvironmentToAttach}
          onAttach={handleAttachEnvironment}
          onDetach={handleDetachEnvironment}
        />
        <FlowAttachmentCard
          icon={<DatabaseIcon className="h-4 w-4 text-subtle" />}
          title="Inherited databases"
          description="Database connections attached here are available to every function step in this Flow."
          emptyText="No databases attached."
          selectValue={databaseToAttach}
          selectPlaceholder="Select database"
          options={databases.map((database) => ({
            id: database.id,
            label: `${database.name} (${database.type})`,
          }))}
          items={flowDatabases.map((attachment) => ({
            id: attachment.databaseId,
            title: attachment.databaseName,
            subtitle: attachment.databaseType,
          }))}
          onSelect={setDatabaseToAttach}
          onAttach={handleAttachDatabase}
          onDetach={handleDetachDatabase}
        />
      </div>

      <div className="flex items-center justify-between">
        <h2 className="text-lg font-medium text-foreground">Versions</h2>
        <Button variant="primary" size="sm" onClick={handleNewDraft} disabled={busy}>
          <PlusIcon className="h-4 w-4" />
          New draft version
        </Button>
      </div>

      <Panel className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-muted-foreground">
              <th className="px-4 py-3 font-medium">Version</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Runtime</th>
              <th className="px-4 py-3 font-medium">Created</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {sortedVersions.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
                  No versions yet. Create a draft to start adding steps.
                </td>
              </tr>
            )}
            {sortedVersions.map((version) => (
              <tr key={version.id} className="border-b border-border last:border-0 hover:bg-surface-hover">
                <td className="px-4 py-3">
                  <Link
                    href={`/flows/${flowId}/versions/${version.id}`}
                    className="font-mono font-medium text-foreground hover:text-muted-strong"
                  >
                    v{version.version}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={version.status} />
                </td>
                <td className="px-4 py-3 text-muted-foreground">{version.runtime}</td>
                <td className="px-4 py-3 text-muted-foreground">{new Date(version.createdAt).toLocaleString()}</td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <Link href={`/flows/${flowId}/versions/${version.id}`} className={buttonClasses("secondary", "sm")}>
                      View
                    </Link>
                    {version.status === "DRAFT" && (
                      <>
                        <Button variant="primary" size="sm" onClick={() => handleAdopt(version)}>
                          <PlayIcon className="h-3.5 w-3.5" />
                          Adopt
                        </Button>
                        <Button variant="danger" size="icon" title="Delete draft" onClick={() => handleDeleteVersion(version)}>
                          <TrashIcon className="h-4 w-4" />
                        </Button>
                      </>
                    )}
                    {version.status === "ADOPTED" && (
                      <Button variant="secondary" size="sm" onClick={() => handleArchive(version)}>
                        <ArchiveIcon className="h-3.5 w-3.5" />
                        Archive
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
    </div>
  );
}

interface FlowAttachmentCardProps {
  icon: ReactNode;
  title: string;
  description: string;
  emptyText: string;
  selectValue: string;
  selectPlaceholder: string;
  options: { id: string; label: string }[];
  items: { id: string; title: string; subtitle: string }[];
  onSelect: (value: string) => void;
  onAttach: () => void;
  onDetach: (id: string) => void;
}

function FlowAttachmentCard({
  icon,
  title,
  description,
  emptyText,
  selectValue,
  selectPlaceholder,
  options,
  items,
  onSelect,
  onAttach,
  onDetach,
}: FlowAttachmentCardProps) {
  return (
    <Panel className="flex flex-col gap-4 p-4">
      <div className="flex items-center gap-2">
        {icon}
        <p className="text-sm font-medium text-foreground">{title}</p>
      </div>
      <p className="text-xs text-muted-foreground">{description}</p>
      <div className="flex gap-2">
        <NativeSelect value={selectValue} onChange={(event) => onSelect(event.target.value)} className="w-full">
          <option value="">{selectPlaceholder}</option>
          {options.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </NativeSelect>
        <Button variant="primary" size="sm" disabled={!selectValue} onClick={onAttach}>
          Attach
        </Button>
      </div>
      {items.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-xs text-muted-foreground">
          {emptyText}
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {items.map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <div>
                <p className="text-sm font-medium text-foreground">{item.title}</p>
                <p className="font-mono text-xs text-muted-foreground">{item.subtitle}</p>
              </div>
              <Button variant="danger" size="icon" title="Detach" onClick={() => onDetach(item.id)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
