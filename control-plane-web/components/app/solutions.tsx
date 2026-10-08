import type { ReactNode } from "react";

export interface Solution {
  id: string;
  name: string;
  said: string;
  /** What to tell the coding agent, finishing "build ...". */
  ask: string;
  thumb: ReactNode;
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 120 76" aria-hidden="true" className="h-auto w-full">
      <rect className="t-f" x="2" y="2" width="116" height="72" rx="8" />
      <path className="t-s" d="M2 17h116" />
      <circle className="t-i" cx="10" cy="9.5" r="2" />
      <circle className="t-i" cx="17" cy="9.5" r="2" />
      {children}
    </svg>
  );
}

// The ten examples from funchole.dev. Examples, not templates: the coding
// agent builds whatever the person asks for.
export const SOLUTIONS: Solution[] = [
  {
    id: "booking",
    name: "Booking system",
    said: "Let customers pick a time.",
    ask: "a booking system where customers pick a time and book a slot",
    thumb: (
      <Frame>
        <g className="t-g">
          <rect x="12" y="26" width="22" height="14" rx="3" />
          <rect x="40" y="26" width="22" height="14" rx="3" />
          <rect x="68" y="26" width="22" height="14" rx="3" />
          <rect x="12" y="48" width="22" height="14" rx="3" />
          <rect x="68" y="48" width="22" height="14" rx="3" />
        </g>
        <rect className="t-b" x="40" y="48" width="22" height="14" rx="3" />
        <rect className="t-y" x="96" y="26" width="14" height="36" rx="3" />
      </Frame>
    ),
  },
  {
    id: "portal",
    name: "Customer portal",
    said: "Give customers a place to log in.",
    ask: "a customer portal where customers sign in to see their invoices and orders",
    thumb: (
      <Frame>
        <circle className="t-y" cx="20" cy="31" r="7" />
        <rect className="t-i" x="32" y="26" width="40" height="4" rx="2" />
        <rect className="t-g" x="32" y="34" width="26" height="4" rx="2" />
        <rect className="t-g" x="12" y="46" width="96" height="9" rx="3" />
        <rect className="t-g" x="12" y="58" width="96" height="9" rx="3" />
        <rect className="t-b" x="90" y="48" width="16" height="5" rx="2.5" />
      </Frame>
    ),
  },
  {
    id: "feedback",
    name: "Feedback board",
    said: "Hear what people want next.",
    ask: "a feedback portal where people post ideas and upvote the ones they want",
    thumb: (
      <Frame>
        <rect className="t-y" x="12" y="25" width="12" height="12" rx="3" />
        <rect className="t-i" x="30" y="29" width="50" height="4" rx="2" />
        <rect className="t-b" x="12" y="42" width="12" height="12" rx="3" />
        <rect className="t-i" x="30" y="46" width="64" height="4" rx="2" />
        <rect className="t-g" x="12" y="59" width="12" height="9" rx="3" />
        <rect className="t-g" x="30" y="61" width="36" height="4" rx="2" />
      </Frame>
    ),
  },
  {
    id: "dashboard",
    name: "Team dashboard",
    said: "Show your team the numbers.",
    ask: "an internal dashboard that shows my team the numbers we check every day",
    thumb: (
      <Frame>
        <g className="t-b">
          <rect x="14" y="46" width="12" height="20" rx="2" />
          <rect x="32" y="38" width="12" height="28" rx="2" />
          <rect x="50" y="50" width="12" height="16" rx="2" />
          <rect x="68" y="30" width="12" height="36" rx="2" />
        </g>
        <rect className="t-y" x="86" y="24" width="22" height="42" rx="3" />
      </Frame>
    ),
  },
  {
    id: "leads",
    name: "Lead form",
    said: "Catch every enquiry.",
    ask: "a lead collection tool: a form that saves every enquiry so I can follow up",
    thumb: (
      <Frame>
        <rect className="t-g" x="14" y="24" width="92" height="10" rx="3" />
        <rect className="t-g" x="14" y="38" width="92" height="10" rx="3" />
        <rect className="t-y" x="14" y="54" width="38" height="12" rx="4" />
      </Frame>
    ),
  },
  {
    id: "admin",
    name: "Admin panel",
    said: "Manage your records.",
    ask: "an admin panel where my team can view and edit our records",
    thumb: (
      <Frame>
        <rect className="t-i" x="10" y="22" width="22" height="46" rx="3" />
        <rect className="t-g" x="38" y="24" width="72" height="8" rx="2" />
        <rect className="t-g" x="38" y="37" width="72" height="8" rx="2" />
        <rect className="t-b" x="38" y="50" width="72" height="8" rx="2" />
        <rect className="t-g" x="38" y="63" width="72" height="5" rx="2" />
      </Frame>
    ),
  },
  {
    id: "api",
    name: "Business API",
    said: "Let your other tools connect.",
    ask: "a business API that other tools and apps can call to read and write our data",
    thumb: (
      <Frame>
        <text x="14" y="52" fontFamily="ui-monospace,Menlo,monospace" fontSize="26" fontWeight="700" style={{ fill: "var(--fh-edge)" }}>
          {"{ }"}
        </text>
        <rect className="t-y" x="70" y="30" width="38" height="8" rx="2" />
        <rect className="t-g" x="70" y="43" width="28" height="6" rx="2" />
        <rect className="t-b" x="70" y="54" width="20" height="6" rx="2" />
      </Frame>
    ),
  },
  {
    id: "saas",
    name: "Small product",
    said: "Launch a small product.",
    ask: "a simple SaaS product with sign-up, a pricing page and a small set of features people use",
    thumb: (
      <Frame>
        <rect className="t-g" x="12" y="26" width="30" height="40" rx="4" />
        <rect className="t-b" x="46" y="22" width="30" height="44" rx="4" />
        <rect className="t-g" x="80" y="26" width="28" height="40" rx="4" />
        <rect className="t-y" x="52" y="52" width="18" height="8" rx="3" />
      </Frame>
    ),
  },
  {
    id: "events",
    name: "Event sign-up",
    said: "Know who's coming.",
    ask: "an event registration page where people sign up and I can see who is coming",
    thumb: (
      <Frame>
        <path className="t-y" d="M16 28h88v8a5 5 0 0 0 0 10v8H16v-8a5 5 0 0 0 0-10z" />
        <rect className="t-i" x="26" y="32" width="36" height="4" rx="2" />
        <rect className="t-i" x="26" y="42" width="24" height="4" rx="2" />
        <path className="t-s" d="M80 30v32" strokeDasharray="3 3" />
      </Frame>
    ),
  },
  {
    id: "tool",
    name: "Custom tool",
    said: "Automate the one-off job.",
    ask: "a custom web tool for one specific job my team does by hand today",
    thumb: (
      <Frame>
        <path className="t-s" d="M14 30h92M14 45h92M14 60h92" />
        <circle className="t-b" cx="40" cy="30" r="5" />
        <circle className="t-y" cx="76" cy="45" r="5" />
        <circle className="t-i" cx="56" cy="60" r="5" />
      </Frame>
    ),
  },
];

// What to paste into the coding agent for a solution.
export function promptFor(solution: Solution) {
  return `Using the FuncHole MCP server: build ${solution.ask} as a working web solution. Create the backend, publish it on my gateway, run a test, and give me the live URL.`;
}
