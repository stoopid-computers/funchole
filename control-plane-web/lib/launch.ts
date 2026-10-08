import { agentConnection } from "@/lib/agent";
import type { ApiKeyResponse, FlowResponse, FunctionResponse, FunctionVersionResponse } from "@/lib/types";

// Where someone is on the way from "just signed up" to "my site is live".
export type LaunchStage = "connect" | "waiting-agent" | "ask" | "building" | "live";

// What the agent's latest work is doing while the stage is "building".
export type BuildState = "drafting" | "publishing" | "ready" | "failed";

export interface LaunchState {
  stage: LaunchStage;
  liveFlows: FlowResponse[];
  /** Set while building: the furthest-along (or failing) piece of work. */
  build: { state: BuildState; functionName?: string } | null;
}

interface LaunchInput {
  keys: ApiKeyResponse[] | undefined;
  functions: FunctionResponse[] | undefined;
  flows: FlowResponse[] | undefined;
  /** Versions per function id (newest first), for the functions we looked at. */
  versions: Record<string, FunctionVersionResponse[]> | undefined;
}

export function launchState({ keys, functions, flows, versions }: LaunchInput): LaunchState {
  const liveFlows = (flows ?? []).filter((flow) => flow.activeFlowVersionStatus === "ADOPTED");
  if (liveFlows.length > 0) return { stage: "live", liveFlows, build: null };

  const hasWork = (functions ?? []).length > 0 || (flows ?? []).length > 0;
  if (hasWork) return { stage: "building", liveFlows, build: buildState(functions ?? [], versions ?? {}) };

  const connection = agentConnection(keys);
  if (connection.state === "none") return { stage: "connect", liveFlows, build: null };
  if (connection.state === "waiting") return { stage: "waiting-agent", liveFlows, build: null };
  return { stage: "ask", liveFlows, build: null };
}

function buildState(functions: FunctionResponse[], versions: Record<string, FunctionVersionResponse[]>) {
  const named = (status: string) =>
    functions.find((fn) => (versions[fn.id] ?? []).some((version) => version.status === status));

  const failed = named("FAILED");
  // A newer READY/PUBLISHING version means the failure was already worked around.
  const latestFailed = failed && (versions[failed.id] ?? [])[0]?.status === "FAILED";
  if (failed && latestFailed) return { state: "failed" as const, functionName: failed.name };
  if (named("PUBLISHING")) return { state: "publishing" as const, functionName: named("PUBLISHING")?.name };
  if (named("READY")) return { state: "ready" as const, functionName: named("READY")?.name };
  return { state: "drafting" as const, functionName: functions[0]?.name };
}

// The request to paste into the agent when something failed to publish.
export function fixPrompt(functionName: string) {
  return `The latest update of "${functionName}" failed to publish on FuncHole. Use the FuncHole MCP tools to read its build logs, fix the problem, and publish it again.`;
}

// The request to paste into the agent when a page misbehaves.
export function fixPagePrompt(pageName: string) {
  return `The page "${pageName}" returned an error when I tried it on FuncHole. Use the FuncHole MCP tools to read its logs, fix it, and publish it again.`;
}
