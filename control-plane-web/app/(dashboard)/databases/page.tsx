"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { Button } from "@/components/Button";
import { Modal } from "@/components/Modal";
import { CopyableCommand } from "@/components/CopyableCommand";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { PlusIcon, TrashIcon, PencilIcon, KeyIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { DatabaseResponse, PaginationResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { confirmAction } from "@/components/ConfirmDialog";
import { NativeSelect } from "@/components/ui/native-select";

const PAGE_SIZE = 10;

const ENGINE_OPTIONS = [
  { value: "POSTGRES", label: "PostgreSQL" },
  { value: "MYSQL", label: "MySQL" },
  { value: "MONGODB", label: "MongoDB" },
  { value: "SUPABASE", label: "Supabase" },
];

interface DatabaseFormState {
  id: string | null;
  name: string;
  type: string;
  host: string;
  port: string;
  databaseName: string;
  username: string;
  password: string;
  sslEnabled: boolean;
}

const EMPTY_FORM: DatabaseFormState = {
  id: null,
  name: "",
  type: "POSTGRES",
  host: "",
  port: "5432",
  databaseName: "",
  username: "",
  password: "",
  sslEnabled: true,
};

export default function DatabasesPage() {
  const [databases, setDatabases] = useState<PaginationResponse<DatabaseResponse> | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<DatabaseFormState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [revealing, setRevealing] = useState<DatabaseResponse | null>(null);
  const [revealedPassword, setRevealedPassword] = useState<string | null>(null);
  const [revealError, setRevealError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listDatabases(page, PAGE_SIZE);
        if (!cancelled) setDatabases(data);
      } catch {
        if (!cancelled) setError("Failed to load databases");
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
    setForm({ ...EMPTY_FORM });
  }

  function openEdit(db: DatabaseResponse) {
    setError(null);
    setForm({
      id: db.id,
      name: db.name,
      type: db.type,
      host: db.host,
      port: String(db.port),
      databaseName: db.databaseName,
      username: db.username,
      password: "",
      sslEnabled: db.sslEnabled,
    });
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
      const port = Number.parseInt(form.port, 10);
      if (form.id) {
        await api.updateDatabase(form.id, {
          name: form.name.trim(),
          host: form.host.trim(),
          port,
          databaseName: form.databaseName.trim(),
          username: form.username.trim(),
          password: form.password.trim() || null,
          sslEnabled: form.sslEnabled,
        });
      } else {
        await api.createDatabase({
          name: form.name.trim(),
          type: form.type,
          host: form.host.trim(),
          port,
          databaseName: form.databaseName.trim(),
          username: form.username.trim(),
          password: form.password,
          sslEnabled: form.sslEnabled,
        });
      }
      closeForm();
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save database");
    } finally {
      setBusy(false);
    }
  }

  async function handleReveal(db: DatabaseResponse) {
    setRevealing(db);
    setRevealedPassword(null);
    setRevealError(null);
    try {
      const result = await api.revealDatabasePassword(db.id);
      setRevealedPassword(result.password);
    } catch (err) {
      setRevealError(err instanceof ApiError ? err.message : "Failed to reveal password");
    }
  }

  function closeReveal() {
    setRevealing(null);
    setRevealedPassword(null);
    setRevealError(null);
  }

  async function handleDelete(db: DatabaseResponse) {
    if (!await confirmAction(`Delete data source "${db.name}"? Anything using it will lose this connection.`)) {
      return;
    }
    setError(null);
    try {
      await api.deleteDatabase(db.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete database");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Configure"
        title="Data Sources"
        description="Managed connection definitions for the databases your actions and workflows need."
        actions={
        <Button variant="primary" onClick={openCreate}>
          <PlusIcon className="h-4 w-4" />
          New data source
        </Button>
        }
      />

      {form && (
        <Modal
          title={form.id ? "Edit data source" : "New data source"}
          description="Store a reusable connection profile. Passwords are protected and can be revealed only through an explicit action."
          onClose={closeForm}
          dismissible={!busy}
          widthClassName="max-w-2xl"
          footer={
            <>
              <Button type="button" variant="secondary" onClick={closeForm} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" form="database-form" variant="primary" disabled={busy}>
                {form.id ? "Save changes" : "Create data source"}
              </Button>
            </>
          }
        >
          <form id="database-form" onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
            <label className={fieldClass}>
              <span className={labelClass}>Name</span>
              <input
                type="text"
                required
                maxLength={150}
                placeholder="primary"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Engine</span>
              <NativeSelect
                value={form.type}
                disabled={form.id !== null}
                onChange={(e) => setForm({ ...form, type: e.target.value })}
                className="w-full" selectClassName="disabled:opacity-50"
              >
                {ENGINE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value} disabled={option.value !== "POSTGRES"}>
                    {option.label}
                    {option.value !== "POSTGRES" ? " (coming soon)" : ""}
                  </option>
                ))}
              </NativeSelect>
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Host</span>
              <input
                type="text"
                required
                placeholder="db.example.com"
                value={form.host}
                onChange={(e) => setForm({ ...form, host: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Port</span>
              <input
                type="number"
                required
                min={1}
                max={65535}
                value={form.port}
                onChange={(e) => setForm({ ...form, port: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Database name</span>
              <input
                type="text"
                required
                value={form.databaseName}
                onChange={(e) => setForm({ ...form, databaseName: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Username</span>
              <input
                type="text"
                required
                value={form.username}
                onChange={(e) => setForm({ ...form, username: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Password{form.id ? " (leave blank to keep current)" : ""}</span>
              <input
                type="password"
                required={!form.id}
                autoComplete="new-password"
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                className={inputClass}
              />
            </label>
            <div className="flex items-start gap-3 rounded-lg border border-border bg-input/30 px-3.5 py-3 sm:col-span-2">
              <Checkbox
                id="database-ssl"
                checked={form.sslEnabled}
                onCheckedChange={(checked) => setForm({ ...form, sslEnabled: checked === true })}
                className="mt-0.5"
              />
              <div className="grid gap-0.5">
                <Label htmlFor="database-ssl" className="cursor-pointer text-sm font-medium text-foreground">
                  Require SSL
                </Label>
                <p className="text-xs text-muted-foreground">Connections to this data source must use TLS.</p>
              </div>
            </div>
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

      <ResourceList title="Data source registry" description="Reusable connection profiles for actions and workflows.">
        {!databases && <ResourceListState>Loading data sources…</ResourceListState>}
        {databases?.items.length === 0 && <ResourceListState>No data sources yet. Create one before attaching data access to customer-facing work.</ResourceListState>}
        {databases?.items.map((db) => (
          <div key={db.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-white/[0.03] lg:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-base font-semibold text-foreground">{db.name}</p>
                <span className="rounded-md bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">{db.type}</span>
                <span className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 text-xs text-muted-foreground">{db.sslEnabled ? "SSL required" : "SSL optional"}</span>
              </div>
              <code className="mt-3 block truncate font-mono text-sm text-muted-strong">
                {db.username}@{db.host}:{db.port}/{db.databaseName}
              </code>
              <p className="mt-2 text-xs text-muted-foreground">Created {new Date(db.createdAt).toLocaleString()}</p>
            </div>
            <div className="flex items-center gap-2 lg:justify-end">
              <Button variant="secondary" size="icon" title="Reveal password" onClick={() => handleReveal(db)}>
                <KeyIcon className="h-4 w-4" />
              </Button>
              <Button variant="secondary" size="icon" title="Edit" onClick={() => openEdit(db)}>
                <PencilIcon className="h-4 w-4" />
              </Button>
              <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(db)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </div>
          </div>
        ))}
      </ResourceList>

      {databases && (
        <Pagination
          page={databases.page}
          totalPages={databases.totalPages}
          totalElements={databases.totalElements}
          onChange={setPage}
        />
      )}

      {revealing && (
        <Modal title={`Password for "${revealing.name}"`} onClose={closeReveal}>
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              <code className="rounded bg-surface-hover px-1 py-0.5 font-mono text-xs">
                {revealing.host}:{revealing.port}/{revealing.databaseName}
              </code>{" "}
              as <span className="font-mono">{revealing.username}</span>
            </p>
            {revealError && (
              <FormError>
                {revealError}
              </FormError>
            )}
            {revealedPassword ? (
              <CopyableCommand value={revealedPassword} />
            ) : (
              !revealError && <p className="text-sm text-muted-foreground">Loading…</p>
            )}
            <Button variant="secondary" className="self-end" onClick={closeReveal}>
              Done
            </Button>
          </div>
        </Modal>
      )}
    </div>
  );
}
