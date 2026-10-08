import { describe, expect, it } from "vitest";
import { BANNED_IN_SIMPLE, GLOSSARY, statusInfo, term } from "@/lib/copy";

describe("glossary", () => {
  it("never uses an engineering word in Simple mode", () => {
    for (const entry of Object.values(GLOSSARY)) {
      const words = entry.simple.toLowerCase().split(/\W+/);
      for (const banned of BANNED_IN_SIMPLE) expect(words).not.toContain(banned);
    }
  });

  it("returns the requested mode", () => {
    expect(term("gateway", "simple")).toBe("Live address");
    expect(term("gateway", "advanced")).toBe("Entry point");
  });
});

describe("statusInfo", () => {
  it("gives a gateway and its certificate different words", () => {
    expect(statusInfo("ACTIVE").label).toBe("Live");
    expect(statusInfo("ACTIVE", "certificate").label).toBe("Secure");
  });

  it("maps failure and in-progress states to plain language", () => {
    expect(statusInfo("FAILED")).toEqual({ label: "Needs attention", tone: "bad" });
    expect(statusInfo("PUBLISHING").tone).toBe("live");
  });

  it("falls back to a readable label for unknown statuses", () => {
    expect(statusInfo("SOMETHING_NEW")).toEqual({ label: "Something_new", tone: "neutral" });
  });
});
