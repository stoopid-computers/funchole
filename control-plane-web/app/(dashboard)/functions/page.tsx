"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { Button, buttonClasses } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { PlusIcon, TrashIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { FunctionResponse, PaginationResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { Modal } from "@/components/Modal";
import { confirmAction } from "@/components/ConfirmDialog";
import { NativeSelect } from "@/components/ui/native-select";

const PAGE_SIZE = 10;

interface FunctionFormState {
  functionKey: string;
  name: string;
  description: string;
  runtime: string;
}

const EMPTY_FORM: FunctionFormState = {
  functionKey: "",
  name: "",
  description: "",
  runtime: "NODE",
};

export default function FunctionsPage() {
  const [functions, setFunctions] = useState<PaginationResponse<FunctionResponse> | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<FunctionFormState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listFunctions(page, PAGE_SIZE);
        if (!cancelled) setFunctions(data);
      } catch {
        if (!cancelled) setError("Failed to load functions");
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
      await api.createFunction({
        functionKey: form.functionKey.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        runtime: form.runtime,
      });
      closeForm();
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create function");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(fn: FunctionResponse) {
    if (!await confirmAction(`Delete function "${fn.name}"?`)) {
      return;
    }
    setError(null);
    try {
      await api.deleteFunction(fn.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete function");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Build"
        title="Actions"
        description="Reusable pieces of work your agent can prepare, test, and connect to customer-facing workflows."
        actions={
        <Button variant="primary" onClick={openCreate}>
          <PlusIcon className="h-4 w-4" />
          New action
        </Button>
        }
      />

      {form && (
        <Modal
          title="New action"
          description="Create the stable action identity. Source and versions are managed after creation."
          onClose={closeForm}
          dismissible={!busy}
          widthClassName="max-w-2xl"
          footer={
            <>
              <Button type="button" variant="secondary" onClick={closeForm} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" form="function-form" variant="primary" disabled={busy}>
                Create action
              </Button>
            </>
          }
        >
          <form id="function-form" onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
            <label className={fieldClass}>
              <span className={labelClass}>Action key</span>
              <input
                type="text"
                required
                maxLength={150}
                placeholder="fn_hello_world"
                pattern="[a-zA-Z0-9_.\-]+"
                value={form.functionKey}
                onChange={(e) => setForm({ ...form, functionKey: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>Name</span>
              <input
                type="text"
                required
                maxLength={255}
                placeholder="Hello World"
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
              <span className={labelClass}>Runtime</span>
              <NativeSelect
                value={form.runtime}
                onChange={(e) => setForm({ ...form, runtime: e.target.value })}
                className="w-full"
              >
                <option value="NODE">NODE</option>
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

      <ResourceList title="Action catalog" description="Open an action to inspect versions, source, runtime context, and tests.">
        {!functions && <ResourceListState>Loading actions…</ResourceListState>}
        {functions?.items.length === 0 && (
          <ResourceListState>No actions yet. Create one manually or let a connected agent prepare the first capability.</ResourceListState>
        )}
        {functions?.items.map((fn) => (
          <div key={fn.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-white/[0.03] lg:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <Link href={`/functions/${fn.id}`} className="text-base font-semibold text-foreground hover:text-muted-strong">
                {fn.name}
              </Link>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <code className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">{fn.functionKey}</code>
                <span className="rounded-md bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">{fn.runtime}</span>
                <span className="text-xs text-muted-foreground">Created {new Date(fn.createdAt).toLocaleString()}</span>
              </div>
              {fn.description && <p className="mt-2 text-sm leading-6 text-muted-foreground">{fn.description}</p>}
            </div>
            <div className="flex items-center gap-2 lg:justify-end">
              <Link href={`/functions/${fn.id}`} className={buttonClasses("secondary", "sm")}>
                Open action
              </Link>
              <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(fn)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </div>
          </div>
        ))}
      </ResourceList>

      {functions && (
        <Pagination
          page={functions.page}
          totalPages={functions.totalPages}
          totalElements={functions.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
