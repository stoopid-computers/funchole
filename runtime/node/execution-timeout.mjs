export class ExecutionTimeoutError extends Error {
  constructor(timeoutMs) {
    super(`Execution exceeded the maximum allowed duration of ${timeoutMs}ms and was aborted`);
    this.name = "ExecutionTimeoutError";
  }
}

// Races fn against a timer so the caller always settles within timeoutMs,
// regardless of what fn itself does. Node cannot forcibly cancel an
// already-running async function, so a slow/hung fn keeps executing in the
// background after the timeout fires - the point is that the CALLER moves
// on without waiting for it, not that fn is actually stopped.
export function runWithTimeout(fn, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new ExecutionTimeoutError(timeoutMs)), timeoutMs);
    Promise.resolve()
      .then(fn)
      .then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (error) => {
          clearTimeout(timer);
          reject(error);
        }
      );
  });
}
