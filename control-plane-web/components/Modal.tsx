"use client";

import type { ReactNode } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

interface ModalProps {
  title: string;
  /** Optional line under the title. */
  description?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  /**
   * Action buttons, pinned below the scrolling body. To submit a form in the
   * body, give the form an `id` and the submit button `form="<that id>"`.
   */
  footer?: ReactNode;
  /** False blocks Esc, outside-click and ✕ - e.g. while a save is in flight. */
  dismissible?: boolean;
  widthClassName?: string;
}

/**
 * The app's one modal, built on shadcn's Dialog (focus trap, Esc, overlay).
 * It's always open while mounted - render it conditionally:
 *
 *   {open && <Modal title="…" onClose={() => setOpen(false)}>…</Modal>}
 */
export function Modal({
  title,
  description,
  onClose,
  children,
  footer,
  dismissible = true,
  widthClassName = "max-w-lg",
}: ModalProps) {
  // Dialog's own `sm:max-w-sm` needs an `sm:`-prefixed override to widen it.
  const width = widthClassName
    .split(/\s+/)
    .map((cls) => (cls.startsWith("max-w-") ? `${cls} sm:${cls}` : cls))
    .join(" ");

  return (
    <Dialog open onOpenChange={(open) => !open && dismissible && onClose()}>
      <DialogContent
        className={cn("max-h-[calc(100dvh-2rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 p-0", width)}
        // With a description Radix links it automatically; without one, opt out
        // explicitly so Radix doesn't warn about a missing description.
        {...(description ? {} : { "aria-describedby": undefined })}
      >
        <DialogHeader className="gap-1 border-b border-border px-5 py-4 pr-12">
          <DialogTitle className="text-base font-medium tracking-tight">{title}</DialogTitle>
          {description && <DialogDescription className="leading-relaxed">{description}</DialogDescription>}
        </DialogHeader>
        <div className="min-h-0 overflow-y-auto p-5">{children}</div>
        {footer && (
          <DialogFooter className="mx-0 mb-0 rounded-b-xl border-t border-border bg-transparent px-5 py-4">
            {footer}
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  );
}
