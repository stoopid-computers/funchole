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
import pg from "pg";
import { DatabasePoolCache } from "./database-pool-cache.mjs";

const rl = createInterface({ input: process.stdin, terminal: false });
let executionQueue = Promise.resolve();

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

  let output;
  try {
    output = await withExecutionContext(executionId, environment, databases, () => handler(parsedInput, invocationContext));
  } catch (error) {
    sendError(executionId, "ARTIFACT_EXECUTION_ERROR", error && error.message ? error.message : String(error));
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

async function withExecutionContext(executionId, environment, databases, callback) {
  const previous = new Map();
  const originalConsole = {
    log: console.log,
    info: console.info,
    warn: console.warn,
    error: console.error,
    debug: console.debug,
  };
  const redactor = createRedactor(environment, databases);
  for (const [key, value] of Object.entries(environment)) {
    previous.set(key, Object.prototype.hasOwnProperty.call(process.env, key) ? process.env[key] : undefined);
    process.env[key] = String(value);
  }
  installConsoleCapture(executionId, redactor);

  try {
    return await callback();
  } finally {
    console.log = originalConsole.log;
    console.info = originalConsole.info;
    console.warn = originalConsole.warn;
    console.error = originalConsole.error;
    console.debug = originalConsole.debug;
    for (const [key, value] of previous.entries()) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}

function installConsoleCapture(executionId, redactor) {
  console.log = (...args) => sendLog(executionId, "stdout", redactor(formatArgs(args)));
  console.info = (...args) => sendLog(executionId, "stdout", redactor(formatArgs(args)));
  console.debug = (...args) => sendLog(executionId, "stdout", redactor(formatArgs(args)));
  console.warn = (...args) => sendLog(executionId, "stderr", redactor(formatArgs(args)));
  console.error = (...args) => sendLog(executionId, "stderr", redactor(formatArgs(args)));
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

  // process.env is process-global, so execution is serialized while applying
  // per-invocation environment overlays. A future worker pool can restore
  // parallelism with stronger isolation.
  executionQueue = executionQueue
    .then(() => handleExecute(message))
    .catch((error) => {
      console.error(`Unhandled error executing ${message.executionId}: ${error && error.message ? error.message : error}`);
    });
});

console.error("Node executor ready");
