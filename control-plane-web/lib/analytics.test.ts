import { afterEach, describe, expect, it, vi } from "vitest";
import { normalizePath, trackOnce } from "@/lib/analytics";

afterEach(() => {
  window.sessionStorage.clear();
  delete (window as unknown as { gtag?: unknown }).gtag;
});

describe("normalizePath", () => {
  it("keeps page names and hides identifiers", () => {
    expect(normalizePath("/activity")).toBe("/activity");
    expect(normalizePath("/flows/3f2b9c1e-1111-2222-3333-444455556666/versions/abc")).toBe("/flows/:id/versions/:id");
  });
});

describe("trackOnce", () => {
  it("sends a funnel step once per session", () => {
    const gtag = vi.fn();
    (window as unknown as { gtag: unknown }).gtag = gtag;
    trackOnce("onboarding_step_ask");
    trackOnce("onboarding_step_ask");
    trackOnce("onboarding_step_live");
    expect(gtag).toHaveBeenCalledTimes(2);
    expect(gtag).toHaveBeenCalledWith("event", "onboarding_step_ask", undefined);
  });
});
