// Target: one locked-down container per tenant, started by docker/sandbox/launch.sh (the same
// script the runtime's Java driver uses). Needs Docker and the funchole-sandbox image.
//
//   docker build -f docker/sandbox/Dockerfile -t funchole-sandbox:dev .
//   SANDBOX_RUNTIME=runsc   (optional) run under gVisor
import { spawn, execFileSync } from "node:child_process";
import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import { mkdtempSync, copyFileSync, readdirSync, rmSync, chmodSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";

const here = import.meta.dirname;
const launcher = resolve(here, "../../../docker/sandbox/launch.sh");
const ARTIFACTS = "/artifacts";
const limits = { memoryMb: 128, timeoutMs: 5000 };

export async function createTarget() {
  // Fixtures become the "artifact volume": mounted read-only, like real artifacts.
  const artifactDir = mkdtempSync(join(tmpdir(), "sbx-artifacts-"));
  const stateDir = mkdtempSync(join(tmpdir(), "sbx-state-"));
  chmodSync(stateDir, 0o755);
  const suites = [resolve(here, "../fixtures"), resolve(here, "../../golden/fixtures")];
  for (const dir of suites) {
    for (const file of readdirSync(dir)) copyFileSync(join(dir, file), join(artifactDir, `${basename(dirname(dir))}-${file}`));
  }
  // The sandbox user is unprivileged, so the "artifact volume" must be readable by others, like the real
  // artifact cache (mkdtemp creates it 0700, which only worked on Docker Desktop).
  chmodSync(artifactDir, 0o755);
  for (const file of readdirSync(artifactDir)) chmodSync(join(artifactDir, file), 0o644);
  const toContainerPath = (fixture) => {
    const dir = dirname(fixture);
    return suites.includes(dir) ? `${ARTIFACTS}/${basename(dirname(dir))}-${basename(fixture)}` : fixture;
  };

  const sandboxes = new Map(); // tenant -> { child, name, pending }

  function start(tenant) {
    const name = `fh-sbx-test-${tenant}-${randomUUID().slice(0, 8)}`;
    const child = spawn(launcher, [name], {
      env: {
        PATH: process.env.PATH,
        HOME: process.env.HOME,
        SANDBOX_ARTIFACT_VOLUME: artifactDir,
        SANDBOX_ARTIFACT_MOUNT: ARTIFACTS,
        SANDBOX_MEMORY: `${limits.memoryMb}m`,
        ...(process.env.SANDBOX_RUNTIME ? { SANDBOX_RUNTIME: process.env.SANDBOX_RUNTIME } : {}),
        ...(process.env.SANDBOX_NETWORK ? { SANDBOX_NETWORK: process.env.SANDBOX_NETWORK } : {}),
        ...(process.env.SANDBOX_DNS ? { SANDBOX_DNS: process.env.SANDBOX_DNS, SANDBOX_STATE_DIR: stateDir } : {}),
        ...(process.env.SANDBOX_ADD_HOSTS ? { SANDBOX_ADD_HOSTS: process.env.SANDBOX_ADD_HOSTS } : {}),
        ...(process.env.SANDBOX_IMAGE ? { SANDBOX_IMAGE: process.env.SANDBOX_IMAGE } : {}),
      },
      stdio: ["pipe", "pipe", "ignore"],
    });
    const sandbox = { child, name, pending: new Map(), dead: false };
    createInterface({ input: child.stdout }).on("line", (line) => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      const entry = sandbox.pending.get(message.executionId);
      if (!entry) return;
      if (message.type === "LOG") { entry.logs.push({ stream: message.stream, message: message.message }); return; }
      sandbox.pending.delete(message.executionId);
      if (message.type === "RESULT") entry.resolve({ output: JSON.parse(message.output), logs: entry.logs });
      if (message.type === "ERROR") entry.resolve({ error: message.error, logs: entry.logs });
    });
    child.on("exit", () => {
      sandbox.dead = true;
      for (const entry of sandbox.pending.values()) entry.resolve({ error: { code: "SANDBOX_TERMINATED", message: "sandbox exited (killed by a limit or crashed)" }, logs: entry.logs });
      sandbox.pending.clear();
    });
    return sandbox;
  }

  const stop = (sandbox) => {
    sandbox.dead = true;
    try { execFileSync("docker", ["rm", "-f", sandbox.name], { stdio: "ignore" }); } catch {}
  };

  async function waitReady(sandbox) {
    // The executor prints nothing on stdout until used; a trivial round trip proves it is up.
    const probe = await send(sandbox, join(ARTIFACTS, "attack-suite-noop.mjs"), {}, {}, 30000);
    return probe.output?.ok;
  }

  function send(sandbox, containerFixture, input, environment, timeoutMs) {
    const executionId = randomUUID();
    return new Promise((resolveResult) => {
      const timer = setTimeout(() => {
        sandbox.pending.delete(executionId);
        stop(sandbox); // a function that outlives its time is killed, and its sandbox is thrown away
        resolveResult({ error: { code: "EXECUTION_TIMEOUT", message: `killed after ${timeoutMs} ms` }, logs: [] });
      }, timeoutMs);
      sandbox.pending.set(executionId, { logs: [], resolve: (value) => { clearTimeout(timer); resolveResult(value); } });
      sandbox.child.stdin.write(JSON.stringify({ type: "EXECUTE", executionId, artifactPath: containerFixture, input: JSON.stringify(input), environment, databases: [] }) + "\n");
    });
  }

  return {
    name: process.env.SANDBOX_RUNTIME ? `sandbox (${process.env.SANDBOX_RUNTIME})` : "sandbox (docker)",
    limits,
    paths: { artifactDir: ARTIFACTS, nodeModulesPg: "/app/node_modules/pg" },
    async execute({ fixture, tenant = "default", input = {}, environment = {}, timeoutMs = limits.timeoutMs }) {
      let sandbox = sandboxes.get(tenant);
      if (!sandbox || sandbox.dead) {
        sandbox = start(tenant);
        sandboxes.set(tenant, sandbox);
        if (!(await waitReady(sandbox))) throw new Error(`sandbox for ${tenant} did not start`);
      }
      return send(sandbox, toContainerPath(fixture), input, environment, timeoutMs);
    },
    async close() {
      for (const sandbox of sandboxes.values()) stop(sandbox);
      rmSync(artifactDir, { recursive: true, force: true });
      rmSync(stateDir, { recursive: true, force: true });
    },
  };
}
