import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "@/components/StatusBadge";

describe("StatusBadge", () => {
  it("shows the raw status by default", () => {
    render(<StatusBadge status="ACTIVE" />);
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });

  it("labels a certificate 'Secure' so it never repeats the gateway's status", () => {
    render(
      <>
        <StatusBadge status="ACTIVE" />
        <StatusBadge status="ACTIVE" kind="certificate" />
      </>
    );
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("Secure")).toBeInTheDocument();
  });
});
