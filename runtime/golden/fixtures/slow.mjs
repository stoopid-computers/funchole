export async function handler() { await new Promise((r) => setTimeout(r, 300)); return { waited: true }; }
