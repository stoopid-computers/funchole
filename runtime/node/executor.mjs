#!/usr/bin/env node
// Persistent Node Executor.
//
// Reads newline-delimited JSON EXECUTE messages from stdin and writes
// newline-delimited JSON RESULT/ERROR messages to stdout. This process is
// started once by the Java Runtime Worker (PersistentNodeExecutor) and stays
// alive across many artifact executions - it never exits after a single
// request.
//
// This script knows nothing about Flow, JetStream, Gateway, or the FuncHole
// database. It receives an artifactPath and input, and executes.

import { createInterface } from "node:readline";
import { existsSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { inspect } from "node:util";
import { AsyncLocalStorage } from "node:async_hooks";
import pg from "pg";
import { DatabasePoolCache } from "./database-pool-cache.mjs";
import { ExecutionTimeoutError, runWithTimeout } from "./execution-timeout.mjs";

const rl = createInterface({ input: process.stdin, terminal: false });
let executionQueue = Promise.resolve();

// A single user Function hanging (an infinite loop, a stuck network call, an
// unresolved promise - anything) must never be able to block every other
// user's work indefinitely: this process handles ALL Functions currently
// routed to this worker, one after another via executionQueue below. Without
// a hard ceiling, one hung execution wedges the queue forever and takes the
// whole platform down for every tenant behind it - confirmed in production
// (2026-09-29): a single Function's stuck database connection froze every
// other invocation platform-wide. See the known-limitation note on
// executionContext below for what this timeout does NOT fully solve.
const EXECUTION_TIMEOUT_MS = Number(process.env.RUNTIME_EXECUTION_TIMEOUT_MS) || 30000;

// Warm connection pools, one per distinct database resource, kept alive for
// the life of this process (see the module header comment) and reused across
// every execution that attaches the same Database - this is what lets
// function authors get a ready client from context.db(name) without paying a
// per-invocation connection cost. See DatabasePoolCache for why a config
// change (sslEnabled, password) still gets a fresh pool.
const databasePools = new DatabasePoolCache((database) => {
  const type = String(database.type || "").toUpperCase();
  if (type !== "POSTGRES") {
    throw new Error(`Unsupported database type: ${database.type}`);
  }

  return new pg.Pool({
    host: database.host,
    port: database.port,
    database: database.databaseName,
    user: database.username,
    password: database.password,
    ssl: database.sslEnabled ? { rejectUnauthorized: false } : false,
  });
});

function buildInvocationContext(databases) {
  const byName = new Map(databases.map((database) => [database.name, database]));
  return {
    db(name) {
      const database = byName.get(name);
      if (!database) {
        throw new Error(`No database attached with name: ${name}`);
      }
      return databasePools.getOrCreate(database);
    },
  };
}

function writeMessage(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function sendLog(executionId, stream, message) {
  writeMessage({ type: "LOG", executionId, stream, message });
}

function sendError(executionId, code, message) {
  writeMessage({ type: "ERROR", executionId, error: { code, message } });
}

// Routes console output to the correct execution's log stream even when a
// timed-out execution's handler is still running in the background while a
// later execution is already active - AsyncLocalStorage tracks the store
// through each execution's own async call chain independently, so a stray
// console.log from stale background work still reaches ITS OWN executionId,
// never the newer one that happens to be running concurrently. This is the
// one piece of isolation EXECUTION_TIMEOUT_MS can fully guarantee.
//
// Known limitation it does NOT solve: process.env is genuinely
// process-global, with no per-async-context equivalent of AsyncLocalStorage.
// The environment overlay below (withEnvironmentOverlay) is still restored
// as soon as an execution's own race settles, so a timed-out handler that
// reads process.env again after being overtaken by later work can observe
// stale or another execution's values - the same class of risk the
// module-level comment already flagged for the pre-timeout, strictly
// serialized design. A stuck handler doing this is already broken; this is
// a narrow, documented edge case, not a regression this fix introduces for
// the common (fast, well-behaved) case, which remains fully serialized.
const executionContext = new AsyncLocalStorage();
const rawConsole = {
  log: console.log.bind(console),
  info: console.info.bind(console),
  warn: console.warn.bind(console),
  error: console.error.bind(console),
  debug: console.debug.bind(console),
};

function routeConsole(stream, args) {
  const store = executionContext.getStore();
  const message = formatArgs(args);
  if (!store) {
    // No active execution (startup/shutdown/parse-error logging) - write for
    // real instead of silently dropping it.
    (stream === "stdout" ? rawConsole.log : rawConsole.error)(message);
    return;
  }
  sendLog(store.executionId, stream, store.redactor(message));
}

console.log = (...args) => routeConsole("stdout", args);
console.info = (...args) => routeConsole("stdout", args);
console.debug = (...args) => routeConsole("stdout", args);
console.warn = (...args) => routeConsole("stderr", args);
console.error = (...args) => routeConsole("stderr", args);

async function handleExecute(message) {
  const { executionId, artifactPath, input } = message;
  const handlerName = message.handler || "handler";
  const environment = message.environment || {};
  const databases = message.databases || [];

  if (!artifactPath || !existsSync(artifactPath)) {
    sendError(executionId, "ARTIFACT_NOT_FOUND", `Artifact not found: ${artifactPath}`);
    return;
  }

  let loadedModule;
  try {
    loadedModule = await import(pathToFileURL(artifactPath).href);
  } catch (error) {
    sendError(executionId, "ARTIFACT_LOAD_ERROR", error && error.message ? error.message : String(error));
    return;
  }

  const handler = loadedModule[handlerName];
  if (typeof handler !== "function") {
    sendError(executionId, "HANDLER_NOT_FOUND", `Artifact does not export a '${handlerName}' function: ${artifactPath}`);
    return;
  }

  let parsedInput;
  try {
    parsedInput = input === undefined || input === null || input === "" ? undefined : JSON.parse(input);
  } catch (error) {
    sendError(executionId, "ARTIFACT_EXECUTION_ERROR", `Failed to parse input JSON: ${error.message}`);
    return;
  }

  const invocationContext = buildInvocationContext(databases);
  const redactor = createRedactor(environment, databases);

  let output;
  try {
    output = await executionContext.run({ executionId, redactor }, () =>
      withEnvironmentOverlay(environment, () =>
        runWithTimeout(() => handler(parsedInput, invocationContext), EXECUTION_TIMEOUT_MS)
      )
    );
  } catch (error) {
    const code = error instanceof ExecutionTimeoutError ? "EXECUTION_TIMEOUT" : "ARTIFACT_EXECUTION_ERROR";
    sendError(executionId, code, error && error.message ? error.message : String(error));
    return;
  }

  let serializedOutput;
  try {
    serializedOutput = JSON.stringify(output);
  } catch (error) {
    sendError(executionId, "OUTPUT_SERIALIZATION_ERROR", error && error.message ? error.message : String(error));
    return;
  }

  writeMessage({ type: "RESULT", executionId, output: serializedOutput });
}

async function withEnvironmentOverlay(environment, callback) {
  const previous = new Map();
  for (const [key, value] of Object.entries(environment)) {
    previous.set(key, Object.prototype.hasOwnProperty.call(process.env, key) ? process.env[key] : undefined);
    process.env[key] = String(value);
  }

  try {
    return await callback();
  } finally {
    for (const [key, value] of previous.entries()) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}

function formatArgs(args) {
  return args.map((arg) => typeof arg === "string" ? arg : inspect(arg, { depth: 6, breakLength: Infinity })).join(" ");
}

function createRedactor(environment, databases) {
  const secrets = Object.values(environment)
    .map((value) => String(value))
    .filter((value) => value.length >= 4);
  for (const database of databases) {
    if (database.password && String(database.password).length >= 4) {
      secrets.push(String(database.password));
    }
  }

  return (message) => {
    let redacted = message;
    for (const secret of secrets) {
      redacted = redacted.split(secret).join("[REDACTED]");
    }
    return redacted;
  };
}

rl.on("line", (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    console.error(`Failed to parse EXECUTE line: ${error.message}`);
    return;
  }

  if (message.type !== "EXECUTE") {
    console.error(`Unsupported message type: ${message.type}`);
    return;
  }

  // Normal (non-timed-out) executions still run one at a time here, in
  // order - executionQueue only moves on to the next item early when the
  // current one exceeds EXECUTION_TIMEOUT_MS, per runWithTimeout above.
  executionQueue = executionQueue
    .then(() => handleExecute(message))
    .catch((error) => {
      console.error(`Unhandled error executing ${message.executionId}: ${error && error.message ? error.message : error}`);
    });
});

rawConsole.error("Node executor ready");
