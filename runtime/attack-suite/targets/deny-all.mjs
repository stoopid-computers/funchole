// Reference target used only to self-check the harness: it declares limits and blocks every
// attack, so the suite must report success. It is NOT a sandbox.
import { basename } from "node:path";

export async function createTarget() {
  return {
    name: "deny-all",
    limits: { memoryMb: 64, timeoutMs: 1000 },
    async execute({ fixture, input = {} }) {
      const name = basename(fixture, ".mjs");
      if (name === "noop") return { output: { ok: true, echo: input } };
      if (name === "tenant-trap" && input.action === "plant") return { output: { planted: true } };
      return { error: { code: "BLOCKED", message: "denied by the reference target" } };
    },
    async close() {},
  };
}
