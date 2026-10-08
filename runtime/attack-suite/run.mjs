#!/usr/bin/env node
// Attack suite for untrusted-code isolation (docs/SANDBOX_ISOLATION_PRD.md, E1).
//
//   node attack-suite/run.mjs --target legacy [--expect-detect]
//
// Every case tries something a malicious function might do and is judged on whether
// the platform let it. A case PASSES when the attack is blocked, FAILS when it worked.
// The suite must FAIL against `legacy` (proving it can detect the holes) and PASS
// against `sandbox`. --expect-detect inverts the exit code for that self-check.
import { createServer } from "node:net";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const args = process.argv.slice(2);
const targetName = args[args.indexOf("--target") + 1] || "legacy";
const expectDetect = args.includes("--expect-detect");

const fixture = (name) => resolve(import.meta.dirname, "fixtures", `${name}.mjs`);
const PLATFORM_ENV = {
  S3_ARTIFACT_ACCESS_KEY: "canary-s3-access-key",
  S3_ARTIFACT_SECRET_KEY: "canary-s3-secret-key",
  S3_ARTIFACT_ENDPOINT: "http://rustfs:9000",
  DB_PASSWORD: "canary-db-password",
  BAO_TOKEN_FILE: "/openbao/bootstrap/canary",
  NATS_URL: "nats://nats:4222",
};

// Stand-ins for internal services, listening on the host loopback.
async function startCanaryServers(count) {
  const servers = [];
  for (let i = 0; i < count; i++) {
    const server = createServer((socket) => socket.destroy());
    await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
    servers.push(server);
  }
  return servers;
}

// ATTACK_INTERNAL_HOSTS="db:5432,nats:4222,..." adds real hosts when run inside the stack.
function envTargets(name) {
  return (process.env[name] || "").split(",").filter(Boolean).map((entry) => {
    const [host, port] = entry.split(":");
    return { host, port: Number(port) };
  });
}

let targetModule;
try {
  targetModule = await import(`./targets/${targetName}.mjs`);
} catch (error) {
  if (error.code !== "ERR_MODULE_NOT_FOUND") throw error;
  console.error(`Target '${targetName}' does not exist yet (the sandbox target is built in E2).`);
  process.exit(2);
}
const target = await targetModule.createTarget({ platformEnv: PLATFORM_ENV });
const scratch = mkdtempSync(join(tmpdir(), "attack-"));
const hostSecretFile = join(scratch, "host-secret.txt");
writeFileSync(hostSecretFile, "canary-host-secret");
const canaries = await startCanaryServers(2);
const loopbackTargets = canaries.map((server) => ({ host: "127.0.0.1", port: server.address().port }));

const run = async (fixtureName, input, extra = {}) => target.execute({ fixture: fixture(fixtureName), input, ...extra });
const verdict = (result) => {
  if (result.error) return { pass: true, detail: `blocked (${result.error.code}: ${String(result.error.message).slice(0, 80)})` };
  return { pass: !result.output.leaked, detail: result.output.detail };
};

const results = [];
const add = (id, title, severity, outcome) => results.push({ id, title, severity, ...outcome });
const skip = (id, title, severity, why) => add(id, title, severity, { skip: true, detail: why });

// Sanity first: a normal function must work, or nothing else means anything.
const sanity = await run("noop", { hello: "world" });
if (!sanity.output?.ok) {
  console.error("Sanity function failed; target is not usable:", JSON.stringify(sanity));
  process.exit(2);
}

add("T1.1", "Platform environment variables are invisible", "critical",
  verdict(await run("env-leak", { platformVars: Object.keys(PLATFORM_ENV) })));
add("T1.2", "Host files and /proc/self/environ are unreadable", "critical",
  verdict(await run("file-read", { hostSecretFile, hostSecretValue: "canary-host-secret", platformValues: Object.values(PLATFORM_ENV) })));
add("T1.3", "Cannot start other processes", "hardening", verdict(await run("spawn", {})));

const internal = [...loopbackTargets, ...envTargets("ATTACK_INTERNAL_HOSTS")];
add("T1.4", "Cannot connect to internal services", "critical", verdict(await run("connect", { targets: internal })));
const names = (process.env.ATTACK_INTERNAL_NAMES || "").split(",").filter(Boolean);
names.length
  ? add("T1.5", "Internal DNS names do not resolve", "critical", verdict(await run("dns", { names })))
  : skip("T1.5", "Internal DNS names do not resolve", "critical", "needs ATTACK_INTERNAL_NAMES (run inside the stack)");

// T1.6: only attack what the target says it limits. No declared limit is itself a failure.
const limits = target.limits;
if (!limits?.memoryMb) {
  add("T1.6a", "Memory is capped", "critical", { pass: false, detail: "target declares no memory limit" });
} else {
  add("T1.6a", "Memory is capped", "critical", verdict(await run("memory-bomb", { mb: limits.memoryMb * 3 }, { timeoutMs: 20000 })));
}
if (!limits?.timeoutMs) {
  add("T1.6b", "Runaway code is stopped by a timeout", "critical", { pass: false, detail: "target declares no timeout" });
} else {
  const spun = await run("spin", {}, { timeoutMs: limits.timeoutMs + 5000 });
  add("T1.6b", "Runaway code is stopped by a timeout", "critical", spun.error ? { pass: true, detail: `stopped (${spun.error.code})` } : { pass: false, detail: "ran forever" });
}

// T1.7: tenant A plants a hook, tenant B runs with a secret, tenant A collects.
const victim = "tenant-b-secret-value";
await run("tenant-trap", { action: "plant" }, { tenant: "tenant-a" });
await run("noop", { who: "b" }, { tenant: "tenant-b", environment: { B_SECRET: victim } });
add("T1.7", "Tenants cannot capture each other's secrets", "critical",
  verdict(await run("tenant-trap", { action: "collect", victimSecret: victim }, { tenant: "tenant-a" })));

const runtimeNode = resolve(import.meta.dirname, "../node");
const writableDirs = target.paths
  ? [target.paths.artifactDir, target.paths.nodeModulesPg]
  : [resolve(import.meta.dirname, "fixtures"), join(runtimeNode, "node_modules", "pg")];
add("T1.8", "Cannot modify artifacts or node_modules", "critical", verdict(await run("write", { dirs: writableDirs })));

skip("T1.9", "Malicious postinstall is blocked at build time", "critical", "covered end to end by BuildSandboxPipelineTest + BuildSandboxIntegrationTest (Gradle, needs Docker)");
const smtp = envTargets("ATTACK_SMTP_TARGETS");
smtp.length
  ? add("T1.10", "SMTP and private-range connections are blocked", "critical", verdict(await run("connect", { targets: smtp })))
  : skip("T1.10", "SMTP and private-range connections are blocked", "critical", "needs ATTACK_SMTP_TARGETS (run inside the stack)");

// E3: what must keep working, and what must stay capped, once the network guard is in place.
const allowedTargets = envTargets("ATTACK_ALLOWED_TARGETS");
if (allowedTargets.length) {
  const reached = (await run("connect", { targets: allowedTargets })).output?.reachable ?? [];
  add("T3.5", "Legitimate destinations stay reachable", "critical", reached.length === allowedTargets.length
    ? { pass: true, detail: `reached: ${reached.join(", ")}` }
    : { pass: false, detail: `reached only [${reached.join(", ")}] of ${allowedTargets.map((t) => `${t.host}:${t.port}`).join(", ")}` });
} else {
  skip("T3.5", "Legitimate destinations stay reachable", "critical", "needs ATTACK_ALLOWED_TARGETS (run inside the guarded network)");
}
const allowedNames = (process.env.ATTACK_ALLOWED_NAMES || "").split(",").filter(Boolean);
if (allowedNames.length) {
  const resolved = (await run("dns", { names: allowedNames })).output?.resolved ?? [];
  add("T3.2", "Public names still resolve", "critical", resolved.length === allowedNames.length
    ? { pass: true, detail: `resolved: ${resolved.join(", ")}` }
    : { pass: false, detail: `resolved only [${resolved.join(", ")}] of ${allowedNames.join(", ")}` });
} else {
  skip("T3.2", "Public names still resolve", "critical", "needs ATTACK_ALLOWED_NAMES (run inside the guarded network)");
}
const directDns = process.env.ATTACK_DIRECT_DNS;
directDns
  ? add("T3.2b", "Cannot query arbitrary DNS servers directly", "critical", verdict(await run("dns-direct", { server: directDns, name: "example.com" })))
  : skip("T3.2b", "Cannot query arbitrary DNS servers directly", "critical", "needs ATTACK_DIRECT_DNS (run inside the guarded network)");
const flood = envTargets("ATTACK_FLOOD_TARGET")[0];
if (flood) {
  const limit = Number(process.env.ATTACK_FLOOD_LIMIT || 256);
  const accepted = (await run("conn-flood", { host: flood.host, port: flood.port, count: limit * 3 }, { timeoutMs: 30000 })).output?.connected ?? -1;
  add("T3.3", "One sandbox cannot open unbounded connections", "critical", accepted >= 0 && accepted <= limit + 8
    ? { pass: true, detail: `${accepted} of ${limit * 3} accepted (limit ${limit})` }
    : { pass: false, detail: `${accepted} of ${limit * 3} accepted, limit is ${limit}` });
} else {
  skip("T3.3", "One sandbox cannot open unbounded connections", "critical", "needs ATTACK_FLOOD_TARGET (run inside the guarded network)");
}

await target.close();
for (const server of canaries) server.close();
rmSync(scratch, { recursive: true, force: true });

const rows = results.map((r) => `${(r.skip ? "SKIP" : r.pass ? "PASS" : "FAIL").padEnd(5)} ${r.id.padEnd(6)} ${r.severity.padEnd(9)} ${r.title}\n            ${r.detail}`);
console.log(`\nAttack suite, target: ${target.name}\n${"=".repeat(60)}\n${rows.join("\n")}\n`);
const failed = results.filter((r) => !r.skip && !r.pass);
const criticalFailed = failed.filter((r) => r.severity === "critical");
console.log(`${results.filter((r) => !r.skip && r.pass).length} passed, ${failed.length} failed (${criticalFailed.length} critical), ${results.filter((r) => r.skip).length} skipped`);

const secure = criticalFailed.length === 0;
if (expectDetect) {
  console.log(secure ? "SELF-CHECK FAILED: suite found nothing wrong with this target" : "SELF-CHECK OK: suite detects the weaknesses of this target");
  process.exit(secure ? 1 : 0);
}
process.exit(secure ? 0 : 1);
