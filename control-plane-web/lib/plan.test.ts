import { describe, expect, it } from "vitest";
import { limitLabel, usageLevel, visibleLimits } from "@/lib/plan";

const usage = (limit: number | null, used: number) => ({ key: "K", limit, used, remaining: limit === null ? null : Math.max(limit - used, 0) });

describe("plan usage", () => {
  it("classifies how close someone is to a limit", () => {
    expect(usageLevel(usage(null, 50))).toBe("unlimited");
    expect(usageLevel(usage(20, 3))).toBe("ok");
    expect(usageLevel(usage(20, 16))).toBe("near");
    expect(usageLevel(usage(20, 20))).toBe("full");
    expect(usageLevel(usage(1, 1))).toBe("full");
  });

  it("names limits for people and falls back to the key", () => {
    expect(limitLabel("MAX_FLOWS")).toBe("Pages & APIs");
    expect(limitLabel("MAX_SOMETHING")).toBe("MAX_SOMETHING");
  });

  it("hides limits that can never be used", () => {
    const limits = [usage(0, 0), usage(1, 0), usage(0, 2)];
    expect(visibleLimits(limits)).toHaveLength(2);
  });
});
