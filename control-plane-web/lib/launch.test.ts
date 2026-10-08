import { describe, expect, it } from "vitest";
import { fixPrompt, launchState } from "@/lib/launch";
import type { ApiKeyResponse, FlowResponse, FunctionResponse, FunctionVersionResponse } from "@/lib/types";

const key = (over: Partial<ApiKeyResponse> = {}): ApiKeyResponse => ({
  id: "k", name: "a", keyPrefix: "fh", createdAt: "2026-10-08T00:00:00Z", lastUsedAt: null, revokedAt: null, ...over,
});
const fn = (id: string, name = "Booking"): FunctionResponse =>
  ({ id, name, functionKey: id, description: "", runtime: "NODE", createdAt: "", updatedAt: "" }) as FunctionResponse;
const flow = (status: string | null): FlowResponse => ({ id: "f", activeFlowVersionStatus: status }) as FlowResponse;
const version = (status: string): FunctionVersionResponse => ({ id: "v", status }) as FunctionVersionResponse;

describe("launchState", () => {
  it("starts by connecting an agent", () => {
    expect(launchState({ keys: [], functions: [], flows: [], versions: {} }).stage).toBe("connect");
  });

  it("waits for an agent whose key was never used", () => {
    expect(launchState({ keys: [key()], functions: [], flows: [], versions: {} }).stage).toBe("waiting-agent");
  });

  it("asks for a first request once the agent has connected", () => {
    const keys = [key({ lastUsedAt: "2026-10-08T01:00:00Z" })];
    expect(launchState({ keys, functions: [], flows: [], versions: {} }).stage).toBe("ask");
  });

  it("is live as soon as one flow has an adopted version", () => {
    const state = launchState({ keys: [], functions: [fn("a")], flows: [flow("ADOPTED"), flow("DRAFT")], versions: {} });
    expect(state.stage).toBe("live");
    expect(state.liveFlows).toHaveLength(1);
  });

  it("reports publishing, ready and failed builds", () => {
    const base = { keys: [], flows: [flow("DRAFT")], functions: [fn("a")] };
    expect(launchState({ ...base, versions: { a: [version("PUBLISHING")] } }).build?.state).toBe("publishing");
    expect(launchState({ ...base, versions: { a: [version("READY")] } }).build?.state).toBe("ready");
    expect(launchState({ ...base, versions: { a: [version("FAILED")] } }).build).toEqual({ state: "failed", functionName: "Booking" });
    expect(launchState({ ...base, versions: { a: [version("DRAFT")] } }).build?.state).toBe("drafting");
  });

  it("ignores an old failure once a newer version is publishing", () => {
    const state = launchState({
      keys: [], flows: [flow("DRAFT")], functions: [fn("a")],
      versions: { a: [version("PUBLISHING"), version("FAILED")] },
    });
    expect(state.build?.state).toBe("publishing");
  });

  it("writes a fix request that names the feature and carries no secrets", () => {
    expect(fixPrompt("Booking")).toContain('"Booking"');
  });
});
