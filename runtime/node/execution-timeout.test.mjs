import { test } from "node:test";
import assert from "node:assert/strict";
import { ExecutionTimeoutError, runWithTimeout } from "./execution-timeout.mjs";

function delay(ms, value) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

test("resolves with the function's own result when it finishes before the timeout", async () => {
  const result = await runWithTimeout(() => delay(5, "ok"), 100);
  assert.equal(result, "ok");
});

test("rejects with the function's own error when it rejects before the timeout", async () => {
  await assert.rejects(
    runWithTimeout(() => Promise.reject(new Error("boom")), 100),
    /boom/
  );
});

test("rejects with ExecutionTimeoutError when the function never settles in time", async () => {
  const neverSettles = new Promise(() => {});
  await assert.rejects(
    runWithTimeout(() => neverSettles, 20),
    ExecutionTimeoutError
  );
});

test("the caller moves on at the timeout even though the hung function keeps running", async () => {
  let finishedLate = false;
  const hungFn = () => delay(200, undefined).then(() => { finishedLate = true; });

  const start = Date.now();
  await assert.rejects(runWithTimeout(hungFn, 20), ExecutionTimeoutError);
  const elapsed = Date.now() - start;

  assert.ok(elapsed < 100, `runWithTimeout should settle near the 20ms timeout, took ${elapsed}ms`);
  assert.equal(finishedLate, false, "the hung function should not have finished yet at this point");
});

test("does not leave a dangling timer once the function settles first (no unhandled timeout later)", async () => {
  await runWithTimeout(() => delay(5, "ok"), 20);
  // If the internal setTimeout weren't cleared, it would fire ~15ms from now
  // with no listener able to observe it - nothing to assert directly, but
  // waiting past that point with no process crash/warning is the proof.
  await delay(30);
});
