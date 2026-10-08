#!/usr/bin/env node
// Golden behaviour of the Node runtime layer (docs/SANDBOX_ISOLATION_PRD.md, T0.6).
//
//   node golden/run.mjs --target legacy
//
// Records what users observe from their functions today: results, error codes/messages, log
// streams, secret redaction, env scoping. The sandbox target must produce exactly the same, so
// this runs against every target and fails on any difference.
import { resolve } from "node:path";

const targetName = process.argv[process.argv.indexOf("--target") + 1] || "legacy";
const fixture = (name) => resolve(import.meta.dirname, "fixtures", `${name}.mjs`);
const { createTarget } = await import(`../attack-suite/targets/${targetName}.mjs`);
const target = await createTarget({ platformEnv: {} });
const missing = "/nonexistent/artifact.mjs";

const cases = [];
const check = (name, fn) => cases.push({ name, fn });
const run = (name, input, extra = {}) => target.execute({ fixture: fixture(name), input, ...extra });
const eq = (actual, expected) => {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a !== e) throw new Error(`expected ${e}\n         got      ${a}`);
};

check("echo returns the input unchanged", async () => eq((await run("echo", { a: 1, b: [2, "x"] })).output, { ok: true, input: { a: 1, b: [2, "x"] } }));
check("request shape (headers, cookie, query) reaches the function", async () =>
  eq((await run("headers", { method: "GET", path: "/p", headers: { "user-agent": "ua", cookie: "s=1" }, query: { name: "n" } })).output, { method: "GET", path: "/p", ua: "ua", cookie: "s=1", q: "n" }));
check("per-invocation env is visible to the function", async () =>
  eq((await run("env", {}, { environment: { GREETING: "hi" } })).output, { greeting: "hi", other: null }));
check("env does not leak into the next invocation", async () => {
  await run("env", {}, { environment: { GREETING: "hi" } });
  eq((await run("env-after", {})).output, { greeting: null });
});
check("secrets are redacted in logs", async () => {
  const result = await run("redaction", {}, { environment: { API_TOKEN: "super-secret-token" } });
  eq(result.logs, [{ stream: "stdout", message: "token is [REDACTED]" }, { stream: "stderr", message: "warn: [REDACTED]" }]);
});
check("console output is captured per stream", async () =>
  eq((await run("logs", {})).logs, [{ stream: "stdout", message: "plain { a: 1 }" }, { stream: "stdout", message: "info line" }, { stream: "stderr", message: "error line" }]));
check("a thrown error becomes ARTIFACT_EXECUTION_ERROR with its message", async () =>
  eq((await run("throws", {})).error, { code: "ARTIFACT_EXECUTION_ERROR", message: "boom from function" }));
check("an unserializable result becomes OUTPUT_SERIALIZATION_ERROR", async () =>
  eq((await run("unserializable", {})).error?.code, "OUTPUT_SERIALIZATION_ERROR"));
check("a missing handler export becomes HANDLER_NOT_FOUND", async () =>
  eq((await run("no-handler", {})).error?.code, "HANDLER_NOT_FOUND"));
check("a missing artifact becomes ARTIFACT_NOT_FOUND", async () =>
  eq((await target.execute({ fixture: "/nonexistent/artifact.mjs", input: {} })).error?.code, "ARTIFACT_NOT_FOUND"));
check("a 1 MB request body round-trips", async () =>
  eq((await run("big", { blob: "x".repeat(1024 * 1024 - 5) + "TAIL!" })).output, { length: 1024 * 1024, tail: "TAIL!" }));
check("async functions are awaited", async () => eq((await run("slow", {})).output, { waited: true }));
check("context.db() without an attached database fails clearly", async () =>
  eq((await run("no-db", {})).error, { code: "ARTIFACT_EXECUTION_ERROR", message: "No database attached with name: missing" }));

let failed = 0;
for (const { name, fn } of cases) {
  try { await fn(); console.log(`PASS  ${name}`); }
  catch (error) { failed++; console.log(`FAIL  ${name}\n      ${error.message}`); }
}
await target.close();
console.log(`\n${cases.length - failed}/${cases.length} golden behaviours match on '${target.name}'`);
process.exit(failed ? 1 : 0);
