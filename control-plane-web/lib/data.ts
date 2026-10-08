"use client";

import useSWR from "swr";
import { api } from "@/lib/api";

// Shared data hooks. SWR caches, de-duplicates and (where useful) polls, so a
// page that is waiting for the agent updates itself without a reload.
export function useApiKeys() {
  return useSWR("api-keys", () => api.listApiKeys(), {
    // Poll while a key exists that has never been used: the agent is being set up.
    refreshInterval: (keys) => (keys?.some((key) => !key.revokedAt && !key.lastUsedAt) ? 4000 : 0),
  });
}

const REFRESH = 8000;

export function useGateways() {
  return useSWR("gateways", () => api.listGateways(1, 5), { refreshInterval: REFRESH });
}

export function useFlows() {
  return useSWR("flows", () => api.listFlows(1, 50), { refreshInterval: REFRESH });
}

export function useFunctions() {
  return useSWR("functions", () => api.listFunctions(1, 10), { refreshInterval: REFRESH });
}

// Newest versions of the given functions, for build progress.
export function useFunctionVersions(functionIds: string[]) {
  return useSWR(
    functionIds.length > 0 ? ["function-versions", ...functionIds] : null,
    async () => {
      const entries = await Promise.all(
        functionIds.map(async (id) => [id, (await api.listFunctionVersions(id, 1, 5)).items] as const)
      );
      return Object.fromEntries(entries);
    },
    { refreshInterval: REFRESH }
  );
}
