// One place for the words users see. Simple mode (the default) never shows
// engineering terms; Advanced mode keeps them. See the glossary in the
// workspace revamp plan.

export type StatusTone = "ok" | "pending" | "live" | "bad" | "neutral";

export const GLOSSARY = {
  gateway: { simple: "Live address", advanced: "Entry point" },
  flow: { simple: "Page or API", advanced: "Workflow" },
  function: { simple: "Feature", advanced: "Action" },
  version: { simple: "Update", advanced: "Version" },
  deploy: { simple: "Publish", advanced: "Deploy" },
  adopt: { simple: "Go live", advanced: "Adopt" },
  archive: { simple: "Take offline", advanced: "Archive" },
  invocation: { simple: "Request", advanced: "Invocation" },
  runTest: { simple: "Try it", advanced: "Run test" },
  environment: { simple: "Settings & secrets", advanced: "Variables & secrets" },
  database: { simple: "Database", advanced: "Data source" },
  apiKey: { simple: "Agent key", advanced: "API key" },
  customDomain: { simple: "Your own domain", advanced: "Custom domain" },
} as const;

export type Mode = "simple" | "advanced";
export type TermKey = keyof typeof GLOSSARY;

export function term(key: TermKey, mode: Mode = "advanced") {
  return GLOSSARY[key][mode];
}

// Words that must never appear in Simple-mode copy.
export const BANNED_IN_SIMPLE = [
  "gateway", "flow", "workflow", "runtime", "invocation", "dsn", "priority", "sha",
  "adopt", "archive", "artifact", "entrypoint", "handler", "payload", "endpoint", "uuid",
] as const;

const STATUS: Record<string, { label: string; tone: StatusTone }> = {
  ACTIVE: { label: "Live", tone: "ok" },
  ADOPTED: { label: "Live", tone: "ok" },
  VERIFIED: { label: "Verified", tone: "ok" },
  READY: { label: "Ready", tone: "ok" },
  COMPLETED: { label: "Done", tone: "ok" },
  PENDING: { label: "Waiting…", tone: "pending" },
  DRAFT: { label: "Draft", tone: "pending" },
  PUBLISHING: { label: "Publishing…", tone: "live" },
  FAILED: { label: "Needs attention", tone: "bad" },
  REJECTED: { label: "Needs attention", tone: "bad" },
  EXPIRED: { label: "Expired", tone: "bad" },
  REVOKED: { label: "Turned off", tone: "bad" },
  ARCHIVED: { label: "Offline", tone: "neutral" },
  INACTIVE: { label: "Offline", tone: "neutral" },
};

// A certificate's ACTIVE is "Secure", not "Live", so a gateway and its
// certificate never show the same word twice.
const CERTIFICATE_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  ACTIVE: { label: "Secure", tone: "ok" },
  PENDING: { label: "Securing…", tone: "pending" },
  FAILED: { label: "Not secure yet", tone: "bad" },
  EXPIRED: { label: "Expired", tone: "bad" },
};

export function statusInfo(status: string, kind?: "certificate") {
  const table = kind === "certificate" ? CERTIFICATE_STATUS : STATUS;
  return table[status] ?? { label: status.charAt(0) + status.slice(1).toLowerCase(), tone: "neutral" as StatusTone };
}
