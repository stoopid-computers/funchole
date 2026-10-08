// Mirrors what the gateway hands a function: method, path, headers, cookies, query.
export async function handler(input) {
  return { method: input.method, path: input.path, ua: input.headers?.["user-agent"], cookie: input.headers?.cookie, q: input.query?.name };
}
