// Sanity: a normal function still works.
export async function handler(input) {
  return { ok: true, echo: input };
}
