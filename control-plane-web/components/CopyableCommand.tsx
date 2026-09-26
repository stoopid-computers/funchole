"use client";

import { useState } from "react";
import { Button } from "@/components/Button";
import { CheckIcon, CopyIcon } from "@/components/icons";

export function CopyableCommand({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access can be denied by the browser - the value is
      // still shown on screen, so this isn't fatal, just a lost convenience.
    }
  }

  return (
    <div className="relative rounded-lg border border-border bg-background">
      {/* pre-wrap (not plain pre): preserves real embedded newlines (e.g. a
          multi-line command) while still wrapping an overly long single
          line instead of forcing horizontal scroll. */}
      <pre className="py-3 pr-12 pl-3.5 font-mono text-xs leading-relaxed break-all whitespace-pre-wrap text-muted-strong">
        {value}
      </pre>
      <Button
        variant="ghost"
        size="icon"
        title={copied ? "Copied" : "Copy"}
        aria-label={copied ? "Copied" : "Copy to clipboard"}
        onClick={copy}
        className="absolute top-1.5 right-1.5 text-subtle hover:text-foreground"
      >
        {copied ? <CheckIcon className="h-4 w-4 text-success" /> : <CopyIcon className="h-4 w-4" />}
      </Button>
    </div>
  );
}
