import { describe, expect, it } from "vitest";
import { BANNED_IN_SIMPLE } from "@/lib/copy";
import { PAGE_COPY } from "@/lib/pagecopy";

describe("Simple-mode page copy", () => {
  it("never uses an engineering word", () => {
    for (const [page, modes] of Object.entries(PAGE_COPY)) {
      for (const [field, text] of Object.entries(modes.simple)) {
        if (typeof text !== "string") continue;
        const words = text.toLowerCase().split(/[^a-z]+/);
        for (const banned of BANNED_IN_SIMPLE) {
          expect(words, `${page}.${field}: "${text}"`).not.toContain(banned);
        }
      }
    }
  });

  it("hides the create button in Simple for things the agent builds", () => {
    expect(PAGE_COPY.flows.simple.create).toBeNull();
    expect(PAGE_COPY.functions.simple.create).toBeNull();
    expect(PAGE_COPY.gateways.simple.create).toBeNull();
    expect(PAGE_COPY.apiKeys.simple.create).not.toBeNull();
  });
});
