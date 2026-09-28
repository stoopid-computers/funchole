"use client";

import { useEffect, useState, type FormEvent, type ReactNode } from "react";
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
  AnthropicIcon,
  OpenAIIcon,
  OpencodeIcon,
  AntigravityIcon,
  PukuIcon,
} from "@/components/icons";
import { api, ApiError, API_BASE_URL, APP_URL } from "@/lib/api";
import type { ApiKeyResponse } from "@/lib/types";
import { FormError } from "@/components/FormError";
import { confirmAction } from "@/components/ConfirmDialog";

// Prefer the Gateway's own <app-url>/mcp shortcut (see FixedHostProxy.PathOverride)
// when this deployment has one configured; otherwise fall back to the
// controlplane API domain's real /api/mcp route, which always works.
const MCP_URL = APP_URL ? `${APP_URL}/mcp` : `${API_BASE_URL}/api/mcp`;

interface AgentCommand {
  name: string;
  icon: (props: { className?: string }) => ReactNode;
  command: (rawKey: string) => string;
  // Where the command/snippet goes, for agents configured via a file instead
  // of a CLI invocation (e.g. Antigravity's mcp_config.json) - shown next to
  // the agent name, kept out of the copyable value itself so what gets
  // copied is exactly the file's contents.
  note?: string;
}

const AGENT_COMMANDS: AgentCommand[] = [
  {
    name: "Claude Code",
    icon: AnthropicIcon,
    command: (rawKey) =>
      `claude mcp add --transport http funchole ${MCP_URL} --header "Authorization: Bearer ${rawKey}"`,
  },
  {
    name: "Codex",
    icon: OpenAIIcon,
    command: (rawKey) =>
      `export FUNCHOLE_MCP_TOKEN=${rawKey}\ncodex mcp add funchole --url ${MCP_URL} --bearer-token-env-var FUNCHOLE_MCP_TOKEN`,
  },
  {
    name: "opencode",
    icon: OpencodeIcon,
    command: (rawKey) =>
      `opencode mcp add funchole --url ${MCP_URL} --header "Authorization=Bearer ${rawKey}"`,
  },
  {
    name: "Antigravity",
    icon: AntigravityIcon,
    // Antigravity has no CLI "add" command - MCP servers are configured via
    // its shared config file, read by the 2.0 IDE, the agy CLI, and the SDK
    // alike.
    note: "~/.gemini/config/mcp_config.json",
    command: (rawKey) =>
      JSON.stringify(
        { mcpServers: { funchole: { serverUrl: MCP_URL, headers: { Authorization: `Bearer ${rawKey}` } } } },
        null,
        2
      ),
  },
  {
    name: "Puku",
    icon: PukuIcon,
    command: (rawKey) =>
      `puku-cli mcp add funchole --transport http ${MCP_URL} -H "Authorization: Bearer ${rawKey}"`,
  },
];

function AgentCommandTabs({ token }: { token: string }) {
  const [activeIndex, setActiveIndex] = useState(0);
  const agent = AGENT_COMMANDS[activeIndex];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-nowrap gap-1.5 overflow-x-auto border-b border-border pb-3">
        {AGENT_COMMANDS.map((item, index) => (
          <button
            key={item.name}
            type="button"
            onClick={() => setActiveIndex(index)}
            className={`flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
              index === activeIndex
                ? "bg-secondary text-foreground"
                : "text-muted-foreground hover:bg-white/[0.04] hover:text-foreground"
            }`}
          >
            <item.icon className="h-4 w-4" />
            {item.name}
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-1.5">
        {agent.note && <span className="font-mono text-xs text-muted-foreground">{agent.note}</span>}
        <CopyableCommand value={agent.command(token)} />
      </div>
    </div>
  );
}

export default function ApiKeysPage() {
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
      } catch {
        if (!cancelled) setError("Failed to load MCP API keys");
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
      setCreateError(err instanceof ApiError ? err.message : "Failed to create MCP API key");
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
      setViewingError(err instanceof ApiError ? err.message : "Failed to load key");
    }
  }

  function closeViewing() {
    setViewingKey(null);
    setViewingToken(null);
    setViewingError(null);
  }

  async function handleRevoke(key: ApiKeyResponse) {
    if (!await confirmAction(`Revoke "${key.name}"? Anything using it (e.g. an MCP client) will stop working immediately.`)) {
      return;
    }
    setError(null);
    try {
      await api.revokeApiKey(key.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to revoke MCP API key");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Configure"
        title="Agent Access"
        description="Credentials for coding agents. Create one key, copy the generated command, and keep the dashboard for manual oversight."
        actions={
        <Button variant="primary" onClick={openCreate}>
          <PlusIcon className="h-4 w-4" />
          New API key
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
              <AgentCommandTabs token={revealedKey} />
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
                  <AgentCommandTabs token={viewingToken} />
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

      <ResourceList title="Agent credentials" description={`Connection endpoint: ${MCP_URL}`}>
        {!keys && <ResourceListState>Loading agent keys…</ResourceListState>}
        {keys?.length === 0 && <ResourceListState>No agent keys yet. Create one to connect a coding agent.</ResourceListState>}
        {keys?.map((key) => (
          <div key={key.id} className="grid gap-4 px-5 py-4 transition-colors hover:bg-white/[0.03] lg:grid-cols-[1fr_auto]">
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
