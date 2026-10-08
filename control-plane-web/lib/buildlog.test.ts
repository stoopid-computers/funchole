import { describe, expect, it } from "vitest";
import { redactLog, summarizeBuildFailure } from "@/lib/buildlog";
import type { FunctionVersionBuildLogResponse } from "@/lib/types";

const log = (over: Partial<FunctionVersionBuildLogResponse>): FunctionVersionBuildLogResponse => ({
  stage: "build", command: "npm run build", exitCode: 1, succeeded: false, timedOut: false, stdout: "", stderr: "boom", createdAt: "", ...over,
});

describe("summarizeBuildFailure", () => {
  it("has nothing to say when every stage succeeded", () => {
    expect(summarizeBuildFailure([log({ succeeded: true })])).toBeNull();
    expect(summarizeBuildFailure(undefined)).toBeNull();
  });

  it("explains the failed stage in plain words", () => {
    expect(summarizeBuildFailure([log({ stage: "npm-install" })])?.summary).toMatch(/packages/);
    expect(summarizeBuildFailure([log({ stage: "build" })])?.summary).toMatch(/Building your page failed/);
    expect(summarizeBuildFailure([log({ timedOut: true })])?.summary).toMatch(/too long/);
    expect(summarizeBuildFailure([log({ stage: "bundle" })])?.summary).toMatch(/"bundle"/);
  });

  it("keeps only the last lines and hides credentials", () => {
    const stderr = Array.from({ length: 30 }, (_, i) => `line ${i}`).join("\n") + "\nAuthorization: Bearer fh_mcp_abc123";
    const failure = summarizeBuildFailure([log({ stderr })]);
    expect(failure?.technical.split("\n")).toHaveLength(12);
    expect(failure?.technical).not.toContain("fh_mcp_abc123");
    expect(redactLog("password=hunter2 ok")).toBe("[hidden] ok");
  });
});
