import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "@/components/StatusBadge";

describe("StatusBadge", () => {
  it("shows a plain-language label and keeps the raw status as a tooltip", () => {
    render(<StatusBadge status="ADOPTED" />);
    expect(screen.getByText("Live")).toHaveAttribute("title", "ADOPTED");
  });

  it("labels a certificate 'Secure' so it never repeats the gateway's status", () => {
    render(
      <>
        <StatusBadge status="ACTIVE" />
        <StatusBadge status="ACTIVE" kind="certificate" />
      </>
    );
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("Secure")).toBeInTheDocument();
  });

  it("flags failures as needing attention", () => {
    render(<StatusBadge status="FAILED" />);
    expect(screen.getByText("Needs attention")).toBeInTheDocument();
  });
});
