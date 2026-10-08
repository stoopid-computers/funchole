import type { PackageLimitUsage } from "@/lib/types";

// What each plan limit is called for people. Keys come from the API.
const LABELS: Record<string, { label: string; unit: string }> = {
  MAX_GATEWAYS: { label: "Live address", unit: "live address" },
  MAX_FLOWS: { label: "Pages & APIs", unit: "page or API" },
  MAX_FUNCTIONS: { label: "Features", unit: "feature" },
  MAX_DOMAINS: { label: "Domains", unit: "domain" },
  MAX_DATABASES: { label: "Databases", unit: "database" },
  MAX_CUSTOM_DOMAINS: { label: "Your own domain", unit: "domain" },
};

export function limitLabel(key: string) {
  return LABELS[key]?.label ?? key;
}

export type UsageLevel = "unlimited" | "ok" | "near" | "full";

// "near" from 80% so there is time to react; "full" when nothing is left.
export function usageLevel({ limit, used }: Pick<PackageLimitUsage, "limit" | "used">): UsageLevel {
  if (limit === null) return "unlimited";
  if (used >= limit) return "full";
  return used / limit >= 0.8 ? "near" : "ok";
}

// Limits a person cannot use anyway (a zero limit with nothing used) are
// noise, e.g. the admin-only domain registry on the free plan.
export function visibleLimits(limits: PackageLimitUsage[]) {
  return limits.filter((limit) => !(limit.limit === 0 && limit.used === 0));
}
