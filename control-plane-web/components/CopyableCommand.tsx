"use client";

import { useState } from "react";
import { track } from "@/lib/analytics";
import { Button } from "@/components/Button";
import { CheckIcon, CopyIcon } from "@/components/icons";

export function CopyableCommand({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      track("copy", { kind: "command" });
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access can be denied by the browser - the value is
      // still shown on screen, so this isn't fatal, just a lost convenience.
    }
  }

  return (
    <div className="relative rounded-lg border border-island-line bg-island-deep">
      {/* pre-wrap (not plain pre): preserves real embedded newlines (e.g. a
          multi-line command) while still wrapping an overly long single
          line instead of forcing horizontal scroll. */}
      <pre className="py-3 pr-12 pl-3.5 font-mono text-xs leading-relaxed break-all whitespace-pre-wrap text-island-code">
        {value}
      </pre>
      <Button
        variant="ghost"
        size="icon"
        title={copied ? "Copied" : "Copy"}
        aria-label={copied ? "Copied" : "Copy to clipboard"}
        onClick={copy}
        className="absolute top-1.5 right-1.5 text-island-mute hover:bg-white/10 hover:text-island-fg"
      >
        {copied ? <CheckIcon className="h-4 w-4 text-island-ok" /> : <CopyIcon className="h-4 w-4" />}
      </Button>
    </div>
  );
}
