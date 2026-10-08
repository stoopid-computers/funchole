// T1.8: can a function modify code the platform trusts (its own artifact dir, node_modules)?
import { writeFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";

export async function handler(input) {
  const written = [];
  for (const dir of input.dirs) {
    const file = join(dir, ".attack-marker");
    try { writeFileSync(file, "x"); written.push(dir); } catch {}
    try { rmSync(file, { force: true }); } catch {}
  }
  return { leaked: written.length > 0, detail: written.length ? `could write: ${written.join(", ")}` : "read-only" };
}
