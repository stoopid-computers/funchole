import { describe, expect, it } from "vitest";
import { capitalize, noun, term } from "@/lib/copy";

describe("modes", () => {
  it("uses everyday words in Simple and the technical words in Advanced", () => {
    expect(noun("flow", "simple")).toBe("page or API");
    expect(noun("flow", "advanced")).toBe("workflow");
    expect(noun("function", "simple", true)).toBe("features");
    expect(noun("gateway", "advanced", true)).toBe("entry points");
    expect(term("deploy", "simple")).toBe("Publish");
  });

  it("capitalises for headings", () => {
    expect(capitalize(noun("flow", "simple", true))).toBe("Pages & APIs");
  });
});
