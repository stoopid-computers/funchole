// T1.6: allocate more than the declared memory limit. Only run against targets that declare limits.
export async function handler(input) {
  const chunks = [];
  for (let i = 0; i < input.mb; i++) chunks.push(Buffer.alloc(1024 * 1024, 1));
  return { leaked: true, detail: `allocated ${input.mb} MB despite limit` };
}
