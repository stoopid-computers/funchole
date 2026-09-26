import type { CSSProperties } from "react";

export function AgentToMcpDiagram() {
  return (
    <div className="relative overflow-hidden rounded-2xl border border-border bg-background p-5 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]">
      <div className="absolute -right-16 -top-16 h-44 w-44 rounded-full bg-accent/20 blur-3xl" />
      <div className="absolute -bottom-20 left-10 h-44 w-44 rounded-full bg-info/10 blur-3xl" />

      <div className="relative flex items-center justify-between eyebrow">
        <span>Coding agent</span>
        <span>MCP bridge</span>
      </div>

      <div className="relative mt-5 aspect-[16/9] rounded-xl border border-border bg-[radial-gradient(circle_at_30%_20%,rgba(107,140,255,0.16),transparent_30%),linear-gradient(135deg,rgba(255,255,255,0.05),rgba(255,255,255,0.01))] p-4">
        <svg viewBox="0 0 640 360" fill="none" className="h-full w-full">
          <defs>
            <linearGradient id="fh-diagram-line" x1="80" y1="174" x2="555" y2="174" gradientUnits="userSpaceOnUse">
              <stop stopColor="#6b8cff" stopOpacity="0.85" />
              <stop offset="0.55" stopColor="#38bdf8" stopOpacity="0.65" />
              <stop offset="1" stopColor="#4ade80" stopOpacity="0.85" />
            </linearGradient>
          </defs>

          <path d="M148 178 C246 78 382 278 492 174" stroke="url(#fh-diagram-line)" strokeWidth="3" strokeDasharray="10 10" strokeLinecap="round" />
          <path d="M148 222 C260 302 392 68 492 122" stroke="rgba(255,255,255,0.12)" strokeWidth="2" strokeDasharray="6 12" strokeLinecap="round" />

          <g>
            <rect x="36" y="84" width="160" height="152" rx="24" fill="rgba(18,18,18,0.92)" stroke="rgba(255,255,255,0.10)" />
            <circle cx="70" cy="120" r="8" fill="#6b8cff" />
            <circle cx="96" cy="120" r="8" fill="rgba(255,255,255,0.18)" />
            <circle cx="122" cy="120" r="8" fill="rgba(255,255,255,0.18)" />
            <path d="M68 162h78M68 188h96M68 214h54" stroke="rgba(250,250,250,0.6)" strokeWidth="8" strokeLinecap="round" />
            <text x="68" y="258" fill="#a3a19b" fontSize="18" fontFamily="monospace">agent asks</text>
          </g>

          <g>
            <rect x="250" y="110" width="140" height="104" rx="22" fill="rgba(107,140,255,0.12)" stroke="rgba(107,140,255,0.34)" />
            <path d="M292 160h56M320 132v56" stroke="#6b8cff" strokeWidth="10" strokeLinecap="round" />
            <text x="282" y="247" fill="#6b8cff" fontSize="18" fontFamily="monospace">MCP key</text>
          </g>

          <g>
            <rect x="444" y="72" width="160" height="198" rx="28" fill="rgba(18,18,18,0.92)" stroke="rgba(255,255,255,0.10)" />
            <path d="M484 126h82M484 158h60M484 190h92" stroke="rgba(250,250,250,0.6)" strokeWidth="8" strokeLinecap="round" />
            <rect x="484" y="218" width="78" height="22" rx="11" fill="rgba(74,222,128,0.12)" stroke="rgba(74,222,128,0.35)" />
            <text x="493" y="235" fill="#4ade80" fontSize="14" fontFamily="monospace">ready</text>
          </g>

          <circle cx="148" cy="178" r="8" fill="#6b8cff" className="fh-pulse" />
          <circle cx="492" cy="174" r="8" fill="#4ade80" className="fh-pulse" />
        </svg>

        <span
          className="absolute left-0 top-0 h-3 w-3 rounded-full bg-brand"
          style={{
            offsetPath: 'path("M 118 170 C 220 72 354 270 462 164")',
            animation: "fh-packet 3.2s linear infinite",
          } as CSSProperties}
        />
      </div>

      <div className="relative mt-4 grid gap-2 text-sm text-muted-foreground sm:grid-cols-3">
        <div className="rounded-xl border border-border bg-surface/70 p-3">
          <span className="block font-medium text-foreground">Access</span>
          Create a secure workspace token.
        </div>
        <div className="rounded-xl border border-border bg-surface/70 p-3">
          <span className="block font-medium text-foreground">Connect</span>
          Use the token from your preferred tool.
        </div>
        <div className="rounded-xl border border-border bg-surface/70 p-3">
          <span className="block font-medium text-foreground">Review</span>
          Check changes before they go live.
        </div>
      </div>
    </div>
  );
}
