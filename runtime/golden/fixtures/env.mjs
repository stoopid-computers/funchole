export async function handler() { return { greeting: process.env.GREETING, other: process.env.NOT_SET ?? null }; }
