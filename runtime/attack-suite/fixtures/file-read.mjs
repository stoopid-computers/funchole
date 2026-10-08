// T1.2: can a function read files outside its own world (host secret, /proc/self/environ)?
import { readFileSync } from "node:fs";

export async function handler(input) {
  const hits = [];
  try {
    if (readFileSync(input.hostSecretFile, "utf8").includes(input.hostSecretValue)) hits.push("host file");
  } catch {}
  try {
    const environ = readFileSync("/proc/self/environ", "utf8");
    if (input.platformValues.some((value) => environ.includes(value))) hits.push("/proc/self/environ");
  } catch {}
  return { leaked: hits.length > 0, detail: hits.length ? `read: ${hits.join(", ")}` : "denied" };
}
