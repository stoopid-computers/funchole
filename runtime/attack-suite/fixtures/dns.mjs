// T1.5: can a function resolve internal service names?
import { lookup } from "node:dns/promises";

export async function handler(input) {
  const resolved = [];
  for (const name of input.names) {
    try { await lookup(name); resolved.push(name); } catch {}
  }
  return { resolved, leaked: resolved.length > 0, detail: resolved.length ? `resolved: ${resolved.join(", ")}` : "none resolve" };
}
