"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { AgentTabs } from "@/components/app/agents";
import { Button } from "@/components/Button";
import { confirmAction } from "@/components/ConfirmDialog";
import { FormError } from "@/components/FormError";
import { TrashIcon } from "@/components/icons";
import { PageHeader } from "@/components/PageHeader";
import { ResourceList } from "@/components/ResourceList";
import { api } from "@/lib/api";
import { agentConnection } from "@/lib/agent";
import { useApiKeys } from "@/lib/data";
import { friendlyError } from "@/lib/errors";
import { usePageCopy } from "@/lib/mode";
import { timeAgo } from "@/lib/time";
import type { ApiKeyResponse } from "@/lib/types";
import { cn } from "@/lib/utils";

function Step({ number, title, done, children }: { number: number; title: string; done?: boolean; children: ReactNode }) {
  return (
    <li className="flex gap-4">
      <span
        aria-hidden="true"
        className={cn(
          "grid size-9 shrink-0 place-items-center rounded-full border-2 border-edge font-heading text-base font-extrabold",
          done ? "bg-live text-[var(--fh-on-sun)]" : "bg-sun text-[var(--fh-on-sun)]"
        )}
      >
        {done ? "✓" : number}
      </span>
      <div className="min-w-0 flex-1">
        <h2 className="font-heading text-xl font-extrabold tracking-tight text-foreground">{title}</h2>
        <div className="mt-3">{children}</div>
      </div>
    </li>
  );
}

// Simple-mode Agent page: connect a coding agent in two steps and watch the
// connection happen. Technical key management lives in Advanced.
export function AgentSetup() {
  const copy = usePageCopy("apiKeys");
  const { data: keys, error: loadError, mutate } = useApiKeys();
  const connection = agentConnection(keys);
  const activeKeys = (keys ?? []).filter((key) => !key.revokedAt);

  const [token, setToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function createKey() {
    setError(null);
    setBusy(true);
    try {
      const created = await api.createApiKey({ name: "My coding agent" });
      setToken(created.rawKey);
      await mutate();
    } catch (err) {
      setError(friendlyError(err, "We couldn't create your key. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function showSetup(key: ApiKeyResponse) {
    setError(null);
    setBusy(true);
    try {
      const revealed = await api.revealApiKey(key.id);
      setToken(revealed.rawKey);
    } catch (err) {
      setError(friendlyError(err, "We couldn't load your key. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(key: ApiKeyResponse) {
    const confirmed = await confirmAction({
      title: `Turn off "${key.name}"?`,
      description: "The coding agent using this key will stop working until you connect it again.",
      confirmLabel: "Turn off",
    });
    if (!confirmed) return;
    setError(null);
    try {
      await api.revokeApiKey(key.id);
      setToken(null);
      await mutate();
    } catch (err) {
      setError(friendlyError(err, "We couldn't turn that key off. Please try again."));
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title={copy.title} description={copy.description} />

      {(error || loadError) && <FormError>{error ?? friendlyError(loadError, "We couldn't load your agent keys.")}</FormError>}

      <div className="sticker flex flex-wrap items-center justify-between gap-4 rounded-2xl bg-card p-5">
        <div className="min-w-0">
          {connection.state === "connected" ? (
            <>
              <p className="inline-flex items-center gap-2 rounded-full border border-live-ink/30 bg-live-soft px-3 py-1 text-sm font-semibold text-live-ink">
                <span className="live-dot" aria-hidden="true" />
                Connected
              </p>
              <p className="mt-2 text-[15px] text-muted-foreground">
                Your agent was last seen {timeAgo(connection.lastUsedAt)}. Ask it to build something.
              </p>
            </>
          ) : connection.state === "waiting" ? (
            <>
              <p className="inline-flex items-center gap-2 rounded-full border border-sun bg-sun-soft px-3 py-1 text-sm font-semibold text-foreground">
                <span className="size-2 rounded-full bg-[#e0a100]" aria-hidden="true" />
                Waiting for your agent…
              </p>
              <p className="mt-2 text-[15px] text-muted-foreground">
                Your key is ready. Paste the command into your agent below. This page updates when it connects.
              </p>
            </>
          ) : (
            <>
              <p className="inline-flex items-center gap-2 rounded-full border border-border bg-muted px-3 py-1 text-sm font-semibold text-muted-foreground">
                Not connected yet
              </p>
              <p className="mt-2 text-[15px] text-muted-foreground">Two quick steps connect your coding agent.</p>
            </>
          )}
        </div>
        {connection.state === "connected" && (
          <Button variant="primary" asChild>
            <Link href="/">Pick what to build</Link>
          </Button>
        )}
      </div>

      <ol className="flex flex-col gap-10">
        <Step number={1} title="Create a key for your agent" done={token !== null || activeKeys.length > 0}>
          {token !== null ? (
            <p className="text-[15px] text-muted-foreground">Your key is ready and filled in below.</p>
          ) : activeKeys.length === 0 ? (
            <div className="flex flex-col items-start gap-2">
              <p className="text-[15px] text-muted-foreground">A key lets one coding agent work in your workspace. You can turn it off any time.</p>
              <Button variant="primary" size="lg" disabled={busy} onClick={createKey}>
                {busy ? "Creating…" : "Create key and show setup"}
              </Button>
            </div>
          ) : (
            <div className="flex flex-col items-start gap-2">
              <p className="text-[15px] text-muted-foreground">You already have a key. Show the setup with your key filled in.</p>
              <Button variant="primary" disabled={busy} onClick={() => showSetup(activeKeys[0])}>
                {busy ? "Loading…" : "Show setup with my key"}
              </Button>
            </div>
          )}
        </Step>

        <Step number={2} title="Add FuncHole to your coding agent">
          <AgentTabs token={token} />
        </Step>

        <Step number={3} title="Ask it to build something">
          <p className="text-[15px] text-muted-foreground">
            Pick an example and paste the request into your agent.{" "}
            <Link href="/" className="font-semibold text-brand underline underline-offset-4">
              Choose what to build
            </Link>
          </p>
        </Step>
      </ol>

      {activeKeys.length > 0 && (
        <ResourceList title={copy.listTitle} description={copy.listDescription}>
          {activeKeys.map((key) => (
            <div key={key.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
              <div className="min-w-0">
                <p className="font-semibold text-foreground">{key.name}</p>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Created {timeAgo(key.createdAt)}
                  {key.lastUsedAt ? ` · last used ${timeAgo(key.lastUsedAt)}` : " · not used yet"}
                </p>
              </div>
              <Button variant="danger" size="icon" title="Turn off this key" aria-label="Turn off this key" onClick={() => revoke(key)}>
                <TrashIcon className="h-4 w-4" />
              </Button>
            </div>
          ))}
        </ResourceList>
      )}
    </div>
  );
}
