"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/Button";
import { StatusBadge } from "@/components/StatusBadge";
import { CopyIcon } from "@/components/icons";
import type { InvocationInspectionResponse, InvocationStepInspectionResponse } from "@/lib/types";

type LogFilter = "all" | "stdout" | "stderr" | "errors";

interface LogTraceConsoleProps {
  invocationId: string;
  currentStatus: string;
  inspection: InvocationInspectionResponse | null;
  onRefresh: () => void;
  refreshing?: boolean;
}

export function LogTraceConsole({ invocationId, currentStatus, inspection, onRefresh, refreshing }: LogTraceConsoleProps) {
  const [filter, setFilter] = useState<LogFilter>("all");
  const [query, setQuery] = useState("");
  const [copied, setCopied] = useState(false);

  const steps = useMemo(() => inspection?.steps ?? [], [inspection]);
  const filteredSteps = useMemo(
    () => steps.map((step) => ({ ...step, logs: step.logs.filter((log) => {
      if (filter === "stdout" && log.stream !== "stdout") return false;
      if (filter === "stderr" && log.stream !== "stderr") return false;
      if (filter === "errors" && log.stream !== "stderr" && !step.error) return false;
      if (query.trim() && !`${log.stream} ${log.message} ${step.error ?? ""}`.toLowerCase().includes(query.trim().toLowerCase())) return false;
      return true;
    }) })),
    [steps, filter, query]
  );

  const logText = filteredSteps
    .flatMap((step) => step.logs.map((log) => `[${step.position}:${step.componentType}:${log.stream}] ${log.message}`))
    .join("\n");

  async function copyLogs() {
    try {
      await navigator.clipboard.writeText(logText || inspection?.result || invocationId);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      // Browser clipboard permissions are best-effort; logs remain visible.
    }
  }

  return (
    <div className="rounded-2xl border border-border bg-background p-4">
      <div className="flex flex-col gap-3 border-b border-border pb-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="eyebrow">Invocation trace</p>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <StatusBadge status={currentStatus} />
            <code className="break-all rounded-md border border-border bg-surface px-1.5 py-0.5 font-mono text-[11px] text-muted-strong">{invocationId}</code>
          </div>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" size="sm" disabled={refreshing} onClick={onRefresh}>
            {refreshing ? "Refreshing…" : "Refresh"}
          </Button>
          <Button variant="secondary" size="sm" onClick={copyLogs}>
            <CopyIcon className="h-3.5 w-3.5" />
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
      </div>

      <div className="mt-4 grid gap-3 md:grid-cols-4">
        <TracePoint label="Accepted" active />
        <TracePoint label="Queued" active={currentStatus === "PENDING" || !!inspection} pulsing={currentStatus === "PENDING"} />
        <TracePoint label="Runtime" active={steps.some((step) => step.startedAt)} pulsing={steps.some((step) => step.status === "RUNNING")} />
        <TracePoint label="Finished" active={currentStatus === "COMPLETED" || currentStatus === "FAILED"} />
      </div>

      <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap gap-1.5">
          {(["all", "stdout", "stderr", "errors"] as LogFilter[]).map((item) => (
            <button
              key={item}
              type="button"
              onClick={() => setFilter(item)}
              className={`rounded-md border px-2.5 py-1 text-xs font-medium capitalize transition-colors ${
                filter === item ? "border-border-strong bg-white/[0.06] text-foreground" : "border-border text-muted-foreground hover:text-foreground"
              }`}
            >
              {item}
            </button>
          ))}
        </div>
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search logs"
          className="h-9 rounded-lg border border-input bg-input/30 px-3 font-mono text-xs text-foreground outline-none transition-colors placeholder:text-subtle focus:border-brand"
        />
      </div>

      {!inspection ? (
        <div className="mt-4 rounded-xl border border-dashed border-border bg-surface/40 p-5">
          <p className="text-sm font-medium text-foreground">Waiting for dispatcher and runtime output</p>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">The invocation was accepted. Logs and result will appear here after the runtime reports back.</p>
        </div>
      ) : (
        <div className="mt-4 space-y-3">
          {inspection.error && (
            <pre className="whitespace-pre-wrap rounded-xl border border-danger/30 bg-danger/10 p-3 font-mono text-xs leading-5 text-danger">{inspection.error}</pre>
          )}
          {inspection.result && (
            <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-xl border border-success/25 bg-success/10 p-3 font-mono text-xs leading-5 text-muted-strong">{inspection.result}</pre>
          )}
          {filteredSteps.length === 0 || filteredSteps.every((step) => step.logs.length === 0 && !step.error) ? (
            <div className="rounded-xl border border-dashed border-border bg-surface/40 p-5">
              <p className="text-sm font-medium text-foreground">No logs match this view</p>
              <p className="mt-1 text-xs text-muted-foreground">Try clearing the search or switching back to all logs.</p>
            </div>
          ) : (
            filteredSteps.map((step) => <StepLogBlock key={step.stepId} step={step} />)
          )}
        </div>
      )}
    </div>
  );
}

function TracePoint({ label, active, pulsing }: { label: string; active: boolean; pulsing?: boolean }) {
  return (
    <div className="rounded-xl border border-border bg-surface/70 p-3">
      <div className="flex items-center gap-2">
        <span className={`h-2.5 w-2.5 rounded-full ${active ? "bg-brand" : "bg-faint"} ${pulsing ? "fh-pulse" : ""}`} />
        <p className="text-sm font-medium text-foreground">{label}</p>
      </div>
    </div>
  );
}

function StepLogBlock({ step }: { step: InvocationStepInspectionResponse }) {
  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface/70">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-3 py-2">
        <div className="flex items-center gap-2">
          <span className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">#{step.position}</span>
          <p className="text-sm font-medium text-foreground">{step.componentType}</p>
        </div>
        <StatusBadge status={step.status} />
      </div>
      {step.error && (
        <pre className="whitespace-pre-wrap border-b border-border bg-danger/10 p-3 font-mono text-xs leading-5 text-danger">{step.error}</pre>
      )}
      {step.logs.length > 0 && (
        <pre className="max-h-72 overflow-auto p-3 font-mono text-[11px] leading-5">
          {step.logs.map((log, index) => (
            <div key={`${log.createdAt}-${index}`} className={log.stream === "stderr" ? "text-danger" : "text-muted-strong"}>
              <span className="select-none text-muted-foreground">{log.stream.padEnd(6)} </span>
              {log.message}
            </div>
          ))}
        </pre>
      )}
    </div>
  );
}
