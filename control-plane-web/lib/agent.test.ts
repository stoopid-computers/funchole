import { describe, expect, it } from "vitest";
import { agentConnection } from "@/lib/agent";
import { timeAgo } from "@/lib/time";
import type { ApiKeyResponse } from "@/lib/types";

const key = (over: Partial<ApiKeyResponse> = {}): ApiKeyResponse => ({
  id: "k",
  name: "Claude Code",
  keyPrefix: "fh_mcp_ab",
  createdAt: "2026-10-08T00:00:00Z",
  lastUsedAt: null,
  revokedAt: null,
  ...over,
});

describe("agentConnection", () => {
  it("has no agent when there are no usable keys", () => {
    expect(agentConnection(undefined)).toEqual({ state: "none" });
    expect(agentConnection([key({ revokedAt: "2026-10-08T01:00:00Z" })])).toEqual({ state: "none" });
  });

  it("waits when a key exists but was never used", () => {
    expect(agentConnection([key()])).toEqual({ state: "waiting" });
  });

  it("is connected with the most recent use", () => {
    const keys = [key({ lastUsedAt: "2026-10-08T02:00:00Z" }), key({ id: "k2", lastUsedAt: "2026-10-08T03:00:00Z" })];
    expect(agentConnection(keys)).toEqual({ state: "connected", lastUsedAt: "2026-10-08T03:00:00Z" });
  });
});

describe("timeAgo", () => {
  const now = Date.parse("2026-10-08T12:00:00Z");
  it("speaks in people units", () => {
    expect(timeAgo("2026-10-08T11:59:50Z", now)).toBe("just now");
    expect(timeAgo("2026-10-08T11:55:00Z", now)).toBe("5 minutes ago");
    expect(timeAgo("2026-10-08T09:00:00Z", now)).toBe("3 hours ago");
    expect(timeAgo("2026-10-06T12:00:00Z", now)).toBe("2 days ago");
  });
});
