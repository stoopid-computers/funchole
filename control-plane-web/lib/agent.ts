import type { ApiKeyResponse } from "@/lib/types";

export type AgentConnection =
  | { state: "none" }
  | { state: "waiting" }
  | { state: "connected"; lastUsedAt: string };

// Whether a coding agent has actually talked to FuncHole: a key that has never
// been used means we are still waiting for the agent to be set up.
export function agentConnection(keys: ApiKeyResponse[] | undefined): AgentConnection {
  const active = (keys ?? []).filter((key) => !key.revokedAt);
  if (active.length === 0) return { state: "none" };
  const used = active.map((key) => key.lastUsedAt).filter((value): value is string => value !== null);
  if (used.length === 0) return { state: "waiting" };
  return { state: "connected", lastUsedAt: used.sort().at(-1) as string };
}
