export async function handler(input) { return { length: input.blob.length, tail: input.blob.slice(-5) }; }
