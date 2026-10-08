import { describe, expect, it } from "vitest";
import { gatewayHost, liveUrl } from "@/lib/urls";

const gateway = { uniqueKey: "agww0t", domainName: "funchole.dev" };

describe("urls", () => {
  it("builds the default host and url", () => {
    expect(gatewayHost(gateway)).toBe("agww0t.funchole.dev");
    expect(liveUrl(gateway, "/orders")).toBe("https://agww0t.funchole.dev/orders");
  });

  it("prefers a custom hostname when given", () => {
    expect(liveUrl(gateway, "/orders", "shop.example.com")).toBe("https://shop.example.com/orders");
    expect(gatewayHost(gateway, null)).toBe("agww0t.funchole.dev");
  });
});
