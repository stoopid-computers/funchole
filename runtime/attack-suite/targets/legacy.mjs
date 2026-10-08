// Target: today's runtime executor, spawned directly (the same process model as PersistentNodeExecutor).
// Deliberately declares NO limits: the suite treats "no limits declared" as a failure for resource attacks.
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";

export const limits = null;

export async function createTarget({ platformEnv }) {
  const executor = resolve(import.meta.dirname, "../../node/executor.mjs");
  const child = spawn(process.execPath, [executor], {
    cwd: resolve(import.meta.dirname, "../../node"),
    env: { PATH: process.env.PATH, HOME: process.env.HOME, ...platformEnv }, // like the runtime container: platform env is present
    stdio: ["pipe", "pipe", "inherit"],
  });
  const pending = new Map();
  createInterface({ input: child.stdout }).on("line", (line) => {
    let message;
    try { message = JSON.parse(line); } catch { return; }
    const entry = pending.get(message.executionId);
    if (!entry) return;
    if (message.type === "LOG") { entry.logs.push({ stream: message.stream, message: message.message }); return; }
    if (message.type === "RESULT") { pending.delete(message.executionId); entry.resolve({ output: JSON.parse(message.output), logs: entry.logs }); }
    if (message.type === "ERROR") { pending.delete(message.executionId); entry.resolve({ error: message.error, logs: entry.logs }); }
  });

  return {
    name: "legacy",
    pid: child.pid,
    limits,
    // tenant is ignored: every tenant shares this one process, which is the point of T1.7.
    execute({ fixture, input = {}, environment = {}, timeoutMs = 5000 }) {
      const executionId = randomUUID();
      return new Promise((resolveResult) => {
        const timer = setTimeout(() => { pending.delete(executionId); resolveResult({ error: { code: "TIMEOUT", message: `no result in ${timeoutMs} ms` } }); }, timeoutMs);
        pending.set(executionId, { logs: [], resolve: (value) => { clearTimeout(timer); resolveResult(value); } });
        child.stdin.write(JSON.stringify({ type: "EXECUTE", executionId, artifactPath: fixture, input: JSON.stringify(input), environment, databases: [] }) + "\n");
      });
    },
    async close() { child.kill("SIGKILL"); },
  };
}
