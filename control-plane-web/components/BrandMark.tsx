import Link from "next/link";
import { useId } from "react";
import { cn } from "@/lib/utils";

interface BrandMarkProps {
  href?: string;
  showText?: boolean;
  className?: string;
}

// The landing page's mark: a white card and an electric-blue glass card overlapping.
export function LogoMark({ className }: { className?: string }) {
  const gradientId = useId();
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={cn("size-[18px]", className)}>
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#b9c8ff" />
          <stop offset="1" stopColor="#2f5bff" />
        </linearGradient>
      </defs>
      <rect x="1.5" y="1.5" width="13.5" height="13.5" rx="4.2" fill="#fafafa" />
      <rect x="9" y="9" width="13.5" height="13.5" rx="4.2" fill={`url(#${gradientId})`} fillOpacity=".92" />
      <path d="M9 13.2A4.2 4.2 0 0 1 13.2 9H15v1.8a4.2 4.2 0 0 1-4.2 4.2H9z" fill="#dfe6ff" />
    </svg>
  );
}

export function BrandMark({ href, showText = true, className }: BrandMarkProps) {
  const content = (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      <LogoMark />
      {showText && <span className="text-[15px] font-semibold tracking-tight text-foreground">FuncHole</span>}
    </span>
  );

  if (!href) return content;

  return (
    <Link href={href} aria-label="FuncHole home" className="rounded-lg">
      {content}
    </Link>
  );
}
