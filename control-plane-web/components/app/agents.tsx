"use client";

import { useState, type KeyboardEvent, type ReactNode } from "react";
import { Button } from "@/components/Button";
import {
  AnthropicIcon,
  AntigravityIcon,
  OpenAIIcon,
  OpencodeIcon,
  PukuIcon,
  WorkflowIcon,
} from "@/components/icons";
import { useToast } from "@/components/Toast";
import { API_BASE_URL, APP_URL } from "@/lib/api";
import { cn } from "@/lib/utils";

// Prefer the Gateway's own <app-url>/mcp shortcut (see FixedHostProxy.PathOverride)
// when this deployment has one configured; otherwise fall back to the
// controlplane API domain's real /api/mcp route, which always works.
export const MCP_URL = APP_URL ? `${APP_URL}/mcp` : `${API_BASE_URL}/api/mcp`;

export const KEY_PLACEHOLDER = "fh_mcp_...";

export interface AgentCommand {
  name: string;
  icon: (props: { className?: string }) => ReactNode;
  command: (rawKey: string) => string;
  /** What to do with the text below, in plain words. */
  where: string;
  /** Extra detail for agents configured through a file or a settings screen. */
  note?: string;
}

export const AGENT_COMMANDS: AgentCommand[] = [
  {
    name: "Claude Code",
    icon: AnthropicIcon,
    where: "Run this in your terminal.",
    command: (key) => `claude mcp add --transport http funchole ${MCP_URL} --header "Authorization: Bearer ${key}"`,
  },
  {
    name: "Codex",
    icon: OpenAIIcon,
    where: "Run these in your terminal.",
    command: (key) =>
      `export FUNCHOLE_MCP_TOKEN=${key}\ncodex mcp add funchole --url ${MCP_URL} --bearer-token-env-var FUNCHOLE_MCP_TOKEN`,
  },
  {
    name: "opencode",
    icon: OpencodeIcon,
    where: "Run this in your terminal.",
    command: (key) => `opencode mcp add funchole --url ${MCP_URL} --header "Authorization=Bearer ${key}"`,
  },
  {
    name: "Cursor",
    icon: WorkflowIcon,
    where: "Add a remote MCP server in Cursor's settings with these details.",
    command: (key) => `URL: ${MCP_URL}\nHeader: Authorization: Bearer ${key}`,
  },
  {
    name: "Antigravity",
    icon: AntigravityIcon,
    where: "Antigravity has no add command. Put this in its MCP config file.",
    // Read by the 2.0 IDE, the agy CLI, and the SDK alike.
    note: "~/.gemini/config/mcp_config.json",
    command: (key) =>
      JSON.stringify({ mcpServers: { funchole: { serverUrl: MCP_URL, headers: { Authorization: `Bearer ${key}` } } } }, null, 2),
  },
  {
    name: "Puku",
    icon: PukuIcon,
    where: "Run this in your terminal.",
    command: (key) => `puku-cli mcp add funchole --transport http ${MCP_URL} -H "Authorization: Bearer ${key}"`,
  },
];

// The landing page's agent picker: sticker tabs and a dark panel with the
// ready-to-paste setup. Without a key the placeholder is highlighted so it is
// obvious what still has to be replaced.
export function AgentTabs({ token }: { token: string | null }) {
  const [index, setIndex] = useState(0);
  const toast = useToast();
  const agent = AGENT_COMMANDS[index];
  const text = agent.command(token ?? KEY_PLACEHOLDER);
  const pieces = token ? [text] : text.split(KEY_PLACEHOLDER);

  function onKeyDown(event: KeyboardEvent, current: number) {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
    if (!step) return;
    event.preventDefault();
    const next = (current + step + AGENT_COMMANDS.length) % AGENT_COMMANDS.length;
    setIndex(next);
    document.getElementById(`agent-tab-${next}`)?.focus();
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      toast(token ? "Copied. Paste it into your coding agent." : "Template copied. Create your key first, then paste it.");
    } catch {
      toast("Copy failed. Select the text instead.");
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div role="tablist" aria-label="Your coding agent" className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {AGENT_COMMANDS.map((item, i) => (
          <button
            key={item.name}
            id={`agent-tab-${i}`}
            role="tab"
            type="button"
            aria-selected={i === index}
            aria-controls="agent-panel"
            tabIndex={i === index ? 0 : -1}
            onClick={() => setIndex(i)}
            onKeyDown={(event) => onKeyDown(event, i)}
            className={cn(
              "flex min-h-14 cursor-pointer items-center justify-center gap-2 rounded-xl border-2 border-edge px-3 font-heading text-base font-extrabold transition-[transform,box-shadow,background-color] duration-100",
              i === index
                ? "translate-y-[3px] bg-sun text-[var(--fh-on-sun)] shadow-press-down"
                : "bg-card text-card-foreground shadow-press hover:bg-sun-soft"
            )}
          >
            <item.icon className="h-5 w-5" />
            {item.name}
          </button>
        ))}
      </div>

      <div
        id="agent-panel"
        role="tabpanel"
        aria-labelledby={`agent-tab-${index}`}
        tabIndex={0}
        className="rounded-2xl border-2 border-edge bg-island p-5 text-island-fg shadow-[6px_6px_0_var(--fh-blue)]"
      >
        <p className="text-[15px] text-island-mute">
          {agent.where}
          {agent.note && <code className="ml-1 font-mono text-[13px] text-island-code">{agent.note}</code>}
          {!token && (
            <>
              {" "}
              Swap the highlighted <code className="font-mono text-[13px] text-island-code">{KEY_PLACEHOLDER}</code> for your key.
            </>
          )}
        </p>
        <pre className="mt-3 rounded-lg border border-island-line bg-island-deep p-4 font-mono text-[13px] leading-relaxed break-all whitespace-pre-wrap text-island-code">
          {pieces.map((piece, i) => (
            <span key={i}>
              {i > 0 && <mark className="rounded bg-sun px-1 text-[var(--fh-on-sun)]">{KEY_PLACEHOLDER}</mark>}
              {piece}
            </span>
          ))}
        </pre>
        <Button variant="sun" className="mt-4" onClick={copy}>
          {token ? "Copy command" : "Copy template"}
        </Button>
      </div>
    </div>
  );
}
