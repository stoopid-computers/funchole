import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { friendlyError } from "@/lib/errors";

describe("friendlyError", () => {
  it("uses the fallback for non-API errors", () => {
    expect(friendlyError(new Error("boom"), "Failed to save")).toBe("Failed to save");
  });

  it("explains network and session problems", () => {
    expect(friendlyError(new ApiError(0, "x"), "f")).toMatch(/connection/);
    expect(friendlyError(new ApiError(401, "x"), "f")).toMatch(/sign in/);
  });

  it("turns plan limits and admin-only rules into plain language", () => {
    const quota = new ApiError(403, "Your package allows up to 1 gateway(s); you already have 1.");
    expect(friendlyError(quota, "f")).toBe("You've reached your plan's limit. Your plan allows up to 1 gateway(s); you already have 1.");
    expect(friendlyError(new ApiError(403, "Only the platform admin can create domains."), "f")).toMatch(/connect your own domain/);
  });

  it("hides ids, and shows validation details", () => {
    expect(friendlyError(new ApiError(404, "Flow not found: 3f2a9c1e-1111-2222-3333-444455556666"), "f")).toBe(
      "We couldn't find that. It may have been deleted."
    );
    expect(friendlyError(new ApiError(422, "Validation failed", ["Name is required"]), "f")).toBe("Name is required");
    expect(friendlyError(new ApiError(500, "Unexpected server error"), "f")).toMatch(/our side/);
    expect(friendlyError(new ApiError(409, "Bad state 3f2a9c1e-1111-2222-3333-444455556666 here"), "f")).toBe("Bad state here");
  });
});
