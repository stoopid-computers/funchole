// T3.2: can a function query an arbitrary DNS server directly (data exfiltration / tunnelling)?
import { Resolver } from "node:dns/promises";

export async function handler(input) {
  const resolver = new Resolver({ timeout: 1500, tries: 1 });
  resolver.setServers([input.server]);
  try {
    const addresses = await resolver.resolve4(input.name);
    return { leaked: addresses.length > 0, detail: `${input.server} answered: ${addresses.join(", ")}` };
  } catch {
    return { leaked: false, detail: `${input.server} did not answer` };
  }
}
