// T1.1: can a function see platform environment variables?
export async function handler(input) {
  const present = input.platformVars.filter((name) => process.env[name] !== undefined);
  return { leaked: present.length > 0, detail: present.length ? `can read: ${present.join(", ")}` : "none visible" };
}
