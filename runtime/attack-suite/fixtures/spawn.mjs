// T1.3 (hardening): can a function start other processes?
import { execSync } from "node:child_process";

export async function handler() {
  try {
    execSync("id", { stdio: "pipe", timeout: 3000 });
    return { leaked: true, detail: "child process started" };
  } catch {
    return { leaked: false, detail: "denied" };
  }
}
