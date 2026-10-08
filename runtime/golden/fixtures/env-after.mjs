// Per-invocation env must not leak into the next invocation.
export async function handler() { return { greeting: process.env.GREETING ?? null }; }
