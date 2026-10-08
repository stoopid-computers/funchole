"use client";

import { useEffect, useState, type FormEvent } from "react";
import { AgentSetup } from "@/components/app/AgentSetup";
import { AgentTabs, MCP_URL } from "@/components/app/agents";
import { Button } from "@/components/Button";
import { Modal } from "@/components/Modal";
import { CopyableCommand } from "@/components/CopyableCommand";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList, ResourceListState } from "@/components/ResourceList";
import { StatusBadge } from "@/components/StatusBadge";
import {
  PlusIcon,
  TrashIcon,
  TerminalIcon,
} from "@/components/icons";
import { api } from "@/lib/api";
import type { ApiKeyResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { confirmAction } from "@/components/ConfirmDialog";
import { friendlyError } from "@/lib/errors";
import { useMode, usePageCopy } from "@/lib/mode";

export default function ApiKeysPage() {
  const { mode } = useMode();
  return mode === "simple" ? <AgentSetup /> : <AdvancedApiKeysPage />;
}

function AdvancedApiKeysPage() {
  const copy = usePageCopy("apiKeys");
  const [keys, setKeys] = useState<ApiKeyResponse[] | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [revealedKey, setRevealedKey] = useState<string | null>(null);
  const [viewingKey, setViewingKey] = useState<ApiKeyResponse | null>(null);
  const [viewingToken, setViewingToken] = useState<string | null>(null);
  const [viewingError, setViewingError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listApiKeys();
        if (!cancelled) setKeys(data);
      } catch (err) {
        if (!cancelled) setError(friendlyError(err, "Failed to load MCP API keys"));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openCreate() {
    setName("");
    setCreateError(null);
    setCreating(true);
  }

  function closeCreate() {
    setCreating(false);
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreateError(null);
    setBusy(true);
    try {
      const created = await api.createApiKey({ name: name.trim() });
      setRevealedKey(created.rawKey);
      setName("");
      setCreating(false);
      refresh();
    } catch (err) {
      setCreateError(friendlyError(err, "Failed to create MCP API key"));
    } finally {
      setBusy(false);
    }
  }

  async function openViewing(key: ApiKeyResponse) {
    setViewingKey(key);
    setViewingToken(null);
    setViewingError(null);
    try {
      const revealed = await api.revealApiKey(key.id);
      setViewingToken(revealed.rawKey);
    } catch (err) {
      setViewingError(friendlyError(err, "Failed to load key"));
    }
  }

  function closeViewing() {
    setViewingKey(null);
    setViewingToken(null);
    setViewingError(null);
  }

  async function handleRevoke(key: ApiKeyResponse) {
    if (!await confirmAction({ title: `Revoke "${key.name}"?`, description: `Anything using it (e.g. an MCP client) will stop working immediately.`, confirmLabel: "Revoke" })) {
      return;
    }
    setError(null);
    try {
      await api.revokeApiKey(key.id);
      refresh();
    } catch (err) {
      setError(friendlyError(err, "Failed to revoke MCP API key"));
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow={copy.eyebrow}
        title={copy.title}
        description={copy.description}
        actions={
        <Button variant="primary" onClick={openCreate}>
          <PlusIcon className="h-4 w-4" />
          {copy.create}
        </Button>
        }
      />

      {revealedKey && (
        <Modal title="Connect your coding agent" onClose={() => setRevealedKey(null)} widthClassName="max-w-xl">
          <div className="flex flex-col gap-5">
            <div className="flex flex-col gap-2">
              <p className="text-sm font-medium text-foreground">
                Your key - you can view it again anytime from the list below.
              </p>
              <CopyableCommand value={revealedKey} />
            </div>

            <div className="flex flex-col gap-2">
              <p className="text-sm font-medium text-foreground">Or paste the ready-to-run command for your agent:</p>
              <AgentTabs token={revealedKey} />
            </div>

            <Button variant="secondary" className="self-end" onClick={() => setRevealedKey(null)}>
              Done
            </Button>
          </div>
        </Modal>
      )}

      {viewingKey && (
        <Modal
          title={`Connect "${viewingKey.name}"`}
          onClose={closeViewing}
          widthClassName="max-w-xl"
        >
          <div className="flex flex-col gap-5">
            {viewingError && <FormError>{viewingError}</FormError>}
            {!viewingError && !viewingToken && (
              <ResourceListState>Loading key…</ResourceListState>
            )}
            {viewingToken && (
              <>
                <div className="flex flex-col gap-2">
                  <p className="text-sm font-medium text-foreground">Full key</p>
                  <CopyableCommand value={viewingToken} />
                </div>
                <div className="flex flex-col gap-2">
                  <p className="text-sm font-medium text-foreground">Or paste the ready-to-run command for your agent:</p>
                  <AgentTabs token={viewingToken} />
                </div>
              </>
            )}
            <Button variant="secondary" className="self-end" onClick={closeViewing}>
              Done
            </Button>
          </div>
        </Modal>
      )}

      {creating && (
        <Modal
          title="Create agent key"
          description="Name the agent or environment that will use this key. The raw token is shown once."
          onClose={closeCreate}
          dismissible={!busy}
          footer={
            <>
              <Button type="button" variant="secondary" onClick={closeCreate} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" form="api-key-form" variant="primary" disabled={busy}>
                {busy ? "Creating…" : "Create key"}
              </Button>
            </>
          }
        >
          <form id="api-key-form" onSubmit={handleCreate} className="flex flex-col gap-4">
            <label className={fieldClass}>
              <span className={labelClass}>Name</span>
              <input
                type="text"
                required
                autoFocus
                maxLength={150}
                placeholder="My coding agent"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className={inputClass}
              />
            </label>
            {createError && <FormError>{createError}</FormError>}
          </form>
        </Modal>
      )}

      {error && (
        <FormError>
          {error}
        </FormError>
      )}

      <ResourceList title={copy.listTitle} description={`${copy.listDescription} Connection endpoint: ${MCP_URL}`.trim()}>
        {!keys && <ResourceListState>{copy.loading}</ResourceListState>}
        {keys?.length === 0 && <ResourceListState>{copy.emptyTitle}. {copy.emptyDescription}</ResourceListState>}
        {keys?.map((key) => (
          <div key={key.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-ink/4 lg:grid-cols-[1fr_auto]">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-base font-semibold text-foreground">{key.name}</p>
                <StatusBadge status={key.revokedAt ? "REVOKED" : "ACTIVE"} />
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <code className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 font-mono">{key.keyPrefix}…</code>
                <span>Created {new Date(key.createdAt).toLocaleString()}</span>
                <span>Last used {key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString() : "never"}</span>
              </div>
            </div>
            <div className="flex items-center gap-2 lg:justify-end">
              <Button variant="secondary" size="icon" title="Connect an agent" onClick={() => openViewing(key)}>
                <TerminalIcon className="h-4 w-4" />
              </Button>
              {!key.revokedAt && (
                <Button variant="danger" size="icon" title="Revoke" onClick={() => handleRevoke(key)}>
                  <TrashIcon className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>
        ))}
      </ResourceList>
    </div>
  );
}
