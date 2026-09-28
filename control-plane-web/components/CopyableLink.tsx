"use client";

import { useState, type MouseEvent, type ReactNode } from "react";
import { CopyIcon, CheckIcon } from "@/components/icons";

interface CopyableLinkProps {
  href: string;
  children: ReactNode;
  className?: string;
}

/**
 * An inline hostname/route display that's both a real link (opens in a new
 * tab) and copyable - for gateway hostnames, flow routes, and custom
 * domains scattered across the dashboard. Stops propagation on both
 * controls so it can safely nest inside a row that's itself a `<Link>` to
 * an internal detail page (opening the external URL must not also trigger
 * that internal navigation).
 */
export function CopyableLink({ href, children, className }: CopyableLinkProps) {
  const [copied, setCopied] = useState(false);

  async function copy(event: MouseEvent) {
    event.preventDefault();
    event.stopPropagation();
    try {
      await navigator.clipboard.writeText(href);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access can be denied by the browser - the value is
      // still shown on screen, so this isn't fatal, just a lost convenience.
    }
  }

  return (
    <span className={`inline-flex min-w-0 items-center gap-1.5 ${className ?? ""}`}>
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        onClick={(event) => event.stopPropagation()}
        className="truncate hover:text-foreground hover:underline underline-offset-2"
      >
        {children}
      </a>
      <button
        type="button"
        onClick={copy}
        title={copied ? "Copied" : "Copy link"}
        aria-label={copied ? "Copied" : "Copy link"}
        className="shrink-0 text-subtle hover:text-foreground"
      >
        {copied ? <CheckIcon className="h-3 w-3 text-success" /> : <CopyIcon className="h-3 w-3" />}
      </button>
    </span>
  );
}
