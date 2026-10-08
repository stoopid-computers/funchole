import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PageLoading } from "@/components/PageLoading";

describe("PageLoading", () => {
  it("shows Loading while there is no error", () => {
    render(<PageLoading error={null} />);
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows the error instead of loading forever", () => {
    render(<PageLoading error="We couldn't find that. It may have been deleted." />);
    expect(screen.getByText(/couldn't find that/)).toBeInTheDocument();
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
  });
});
