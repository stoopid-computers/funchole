import Link from "next/link";
import { useId } from "react";
import { cn } from "@/lib/utils";

interface BrandMarkProps {
  href?: string;
  showText?: boolean;
  className?: string;
}

// The landing page's mark: two overlapping rounded squares. The back square
// and outline follow the text colour, so it reads on paper and on ink.
export function LogoMark({ className }: { className?: string }) {
  const gradientId = useId();
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={cn("size-6", className)}>
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#c5d0ff" />
          <stop offset="1" stopColor="#2f5bff" />
        </linearGradient>
      </defs>
      <rect x="1.5" y="1.5" width="13.5" height="13.5" rx="4.2" style={{ fill: "var(--fh-ink)" }} />
      <rect x="9" y="9" width="13.5" height="13.5" rx="4.2" fill={`url(#${gradientId})`} strokeWidth="1.5" style={{ stroke: "var(--fh-ink)" }} />
      <path d="M9 13.2A4.2 4.2 0 0 1 13.2 9H15v1.8a4.2 4.2 0 0 1-4.2 4.2H9z" fill="#dfe6ff" />
    </svg>
  );
}

export function BrandMark({ href, showText = true, className }: BrandMarkProps) {
  const content = (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <LogoMark />
      {showText && <span className="font-heading text-xl font-extrabold tracking-tight text-foreground">FuncHole</span>}
    </span>
  );

  if (!href) return content;

  return (
    <Link href={href} aria-label="FuncHole home" className="rounded-lg">
      {content}
    </Link>
  );
}
