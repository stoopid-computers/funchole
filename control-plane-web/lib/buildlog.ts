import type { FunctionVersionBuildLogResponse } from "@/lib/types";

// Lines that may carry credentials never leave the log summary.
const SENSITIVE = /(bearer\s+\S+|token\s*[=:]\s*\S+|password\s*[=:]\s*\S+|secret\s*[=:]\s*\S+|fh_mcp_\w+)/gi;

export function redactLog(text: string) {
  return text.replace(SENSITIVE, "[hidden]");
}

export interface BuildFailure {
  /** One plain sentence about what went wrong. */
  summary: string;
  /** The last lines of error output, for people who want to look. */
  technical: string;
}

// Turns the stages of a failed build into a sentence a non-developer can act
// on (usually: ask the agent to fix it), plus a short redacted excerpt.
export function summarizeBuildFailure(logs: FunctionVersionBuildLogResponse[] | undefined): BuildFailure | null {
  const failed = (logs ?? []).find((log) => !log.succeeded);
  if (!failed) return null;

  const stage = failed.stage.toLowerCase();
  const summary = failed.timedOut
    ? "Building your page took too long and was stopped."
    : stage.includes("install") || stage.includes("depend")
      ? "Setting up the code's packages failed."
      : stage.includes("build") || stage.includes("compile")
        ? "Building your page failed."
        : stage.includes("test")
          ? "A check on your page failed before it could go live."
          : `Publishing stopped at the "${failed.stage}" step.`;

  const excerpt = redactLog((failed.stderr || failed.stdout || "").trim()).split("\n").slice(-12).join("\n");
  return { summary, technical: excerpt };
}
