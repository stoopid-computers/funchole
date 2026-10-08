import { describe, expect, it } from "vitest";
import { capitalize, noun, term } from "@/lib/copy";
import { ADVANCED_ENABLED, saveMode } from "@/lib/mode";

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

describe("advanced is parked", () => {
  it("cannot be switched on while ADVANCED_ENABLED is false", () => {
    expect(ADVANCED_ENABLED).toBe(false);
    localStorage.removeItem("fh_mode");
    saveMode("advanced");
    expect(localStorage.getItem("fh_mode")).toBeNull();
  });
});
