#!/usr/bin/env node
// Performance baseline of the Node runtime layer (docs/SANDBOX_ISOLATION_PRD.md, T0.5).
//   node golden/bench.mjs --target legacy [--n 500]
// Prints cold start, warm invoke p50/p95/p99 and resident memory. The sandbox target is compared
// against these numbers (T2.20).
import { resolve } from "node:path";
import { execSync } from "node:child_process";

const arg = (name, fallback) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : fallback; };
const targetName = arg("--target", "legacy");
const n = Number(arg("--n", 500));
const fixture = (name) => resolve(import.meta.dirname, "fixtures", `${name}.mjs`);
const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
const ms = (v) => `${v.toFixed(2)} ms`;

const { createTarget } = await import(`../attack-suite/targets/${targetName}.mjs`);
const t0 = performance.now();
const target = await createTarget({ platformEnv: {} });
const first = await target.execute({ fixture: fixture("echo"), input: { warm: false } });
const coldStart = performance.now() - t0;
if (!first.output?.ok) { console.error("first call failed", first); process.exit(2); }

async function series(name, input, count) {
  const times = [];
  for (let i = 0; i < count; i++) {
    const s = performance.now();
    await target.execute({ fixture: fixture(name), input });
    times.push(performance.now() - s);
  }
  return times.sort((a, b) => a - b);
}

const small = await series("echo", { a: 1 }, n);
const big = await series("big", { blob: "x".repeat(256 * 1024) }, Math.max(20, Math.floor(n / 10)));
let rss = "n/a";
try { rss = `${(Number(execSync(`ps -o rss= -p ${target.pid ?? process.pid}`).toString().trim()) / 1024).toFixed(0)} MB`; } catch {}

console.log(`Target: ${target.name}  (n=${n})`);
console.log(`cold start (spawn + first call): ${ms(coldStart)}`);
console.log(`warm echo     p50 ${ms(pct(small, 50))}  p95 ${ms(pct(small, 95))}  p99 ${ms(pct(small, 99))}`);
console.log(`warm 256 KB   p50 ${ms(pct(big, 50))}  p95 ${ms(pct(big, 95))}`);
console.log(`executor RSS: ${rss}`);
await target.close();
