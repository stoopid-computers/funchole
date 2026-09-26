"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { panelClass, Panel } from "@/components/Panel";
import { Button, buttonClasses } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { ArrowLeftIcon, ChevronRightIcon, PlusIcon, PencilIcon, TrashIcon, CopyIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { FunctionResponse, FunctionVersionResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { confirmAction } from "@/components/ConfirmDialog";
import { NativeSelect } from "@/components/ui/native-select";

interface EditFormState {
  name: string;
  description: string;
  runtime: string;
}

export default function FunctionDetailPage() {
  const params = useParams<{ functionId: string }>();
  const router = useRouter();
  const functionId = params.functionId;

  const [fn, setFn] = useState<FunctionResponse | null>(null);
  const [versions, setVersions] = useState<FunctionVersionResponse[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [editForm, setEditForm] = useState<EditFormState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.getFunction(functionId);
        if (!cancelled) setFn(data);
      } catch {
        if (!cancelled) setError("Failed to load function");
      }
      try {
        const versionData = await api.listFunctionVersions(functionId, 1, 50);
        if (!cancelled) setVersions(versionData.items);
      } catch {
        if (!cancelled) setVersions([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [functionId, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openEdit() {
    if (!fn) return;
    setEditForm({ name: fn.name, description: fn.description ?? "", runtime: fn.runtime });
  }

  async function handleEditSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editForm) return;
    setError(null);
    setBusy(true);
    try {
      await api.updateFunction(functionId, {
        name: editForm.name.trim(),
        description: editForm.description.trim() || null,
        runtime: editForm.runtime,
      });
      setEditForm(null);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to update function");
    } finally {
      setBusy(false);
    }
  }

  async function handleDeleteFunction() {
    if (!fn || !await confirmAction(`Delete function "${fn.name}"? This cannot be undone.`)) return;
    setError(null);
    try {
      await api.deleteFunction(functionId);
      router.replace("/functions");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete function");
    }
  }

  async function handleNewDraft(copyFromVersionId?: string) {
    setError(null);
    setBusy(true);
    try {
      const version = await api.createFunctionVersion(functionId, { runtime: fn?.runtime ?? "NODE" });
      const destination = copyFromVersionId
        ? `/functions/${functionId}/versions/${version.id}?copyFrom=${copyFromVersionId}`
        : `/functions/${functionId}/versions/${version.id}`;
      router.push(destination);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create draft version");
      setBusy(false);
    }
  }

  if (!fn) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }

  const sortedVersions = [...versions].sort((a, b) => b.version - a.version);

  return (
    <div className="flex flex-col gap-6">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link href="/functions" className="hover:text-foreground">
          Functions
        </Link>
        <ChevronRightIcon className="h-3.5 w-3.5" />
        <span className="text-foreground">{fn.name}</span>
      </nav>

      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          <Link
            href="/functions"
            className="mt-1 flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground"
          >
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
          <div>
            <h1 className="text-2xl font-medium tracking-tight text-foreground">{fn.name}</h1>
            <p className="mt-1 flex items-center gap-2 font-mono text-xs text-muted-foreground">
              {fn.functionKey}
              <span className="text-border-strong">&middot;</span>
              {fn.runtime}
            </p>
            {fn.description && <p className="mt-1 text-sm text-muted-foreground">{fn.description}</p>}
          </div>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={openEdit}>
            <PencilIcon className="h-4 w-4" />
            Edit
          </Button>
          <Button variant="danger" onClick={handleDeleteFunction}>
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
            <span className={labelClass}>Runtime</span>
            <NativeSelect
              value={editForm.runtime}
              onChange={(e) => setEditForm({ ...editForm, runtime: e.target.value })}
              className="w-full"
            >
              <option value="NODE">NODE</option>
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

      <div className="flex items-center justify-between">
        <h2 className="text-lg font-medium text-foreground">Versions</h2>
        <Button variant="primary" size="sm" onClick={() => handleNewDraft()} disabled={busy}>
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
              <th className="px-4 py-3 font-medium">Artifact</th>
              <th className="px-4 py-3 font-medium">Created</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {sortedVersions.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
                  No versions yet. Create a draft to submit source and deploy.
                </td>
              </tr>
            )}
            {sortedVersions.map((version) => (
              <tr key={version.id} className="border-b border-border last:border-0 hover:bg-surface-hover">
                <td className="px-4 py-3">
                  <Link
                    href={`/functions/${functionId}/versions/${version.id}`}
                    className="font-mono font-medium text-foreground hover:text-muted-strong"
                  >
                    v{version.version}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={version.status} />
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted-foreground">
                  {version.artifactSha256 ? `${version.artifactSha256.slice(0, 10)}…` : "—"}
                </td>
                <td className="px-4 py-3 text-muted-foreground">{new Date(version.createdAt).toLocaleString()}</td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <Button
                      variant="secondary"
                      size="icon"
                      title="New draft version from this one"
                      disabled={busy}
                      onClick={() => handleNewDraft(version.id)}
                    >
                      <CopyIcon className="h-4 w-4" />
                    </Button>
                    <Link href={`/functions/${functionId}/versions/${version.id}`} className={buttonClasses("secondary", "sm")}>
                      View
                    </Link>
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
