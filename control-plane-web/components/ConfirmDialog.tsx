"use client";

import { useEffect, useState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { track } from "@/lib/analytics";

export interface ConfirmOptions {
  title: string;
  description?: string;
  /** Label of the confirm button, e.g. "Delete". */
  confirmLabel: string;
}

interface ConfirmRequest extends ConfirmOptions {
  resolve: (confirmed: boolean) => void;
}

let enqueue: ((request: ConfirmRequest) => void) | null = null;

/**
 * Drop-in, promise-based replacement for `window.confirm`, rendered as a
 * shadcn AlertDialog by the <ConfirmHost /> in the dashboard layout. Falls
 * back to the native dialog if no host is mounted.
 *
 * The message's first sentence ("Delete flow "x"?") becomes the title and the
 * rest the description; its first word becomes the confirm button's label.
 */
export function confirmAction(options: ConfirmOptions | string): Promise<boolean> {
  const resolved = typeof options === "string" ? fromMessage(options) : options;
  const action = resolved.confirmLabel.toLowerCase();
  const done = (confirmed: boolean) => {
    track(confirmed ? "confirm_accept" : "confirm_cancel", { action });
    return confirmed;
  };
  if (!enqueue) return Promise.resolve(window.confirm(`${resolved.title} ${resolved.description ?? ""}`.trim())).then(done);
  const push = enqueue;
  return new Promise<boolean>((resolve) => push({ ...resolved, resolve })).then(done);
}

// Legacy one-string form: the first sentence is the title, the rest the
// description and the first word the button label. Prefer the object form.
function fromMessage(message: string): ConfirmOptions {
  const end = message.indexOf("?");
  const title = end === -1 ? message : message.slice(0, end + 1);
  const description = end === -1 ? undefined : message.slice(end + 1).trim() || undefined;
  return { title, description, confirmLabel: /^(\w+)/.exec(title)?.[1] ?? "Confirm" };
}

export function ConfirmHost() {
  const [request, setRequest] = useState<ConfirmRequest | null>(null);

  useEffect(() => {
    enqueue = setRequest;
    return () => {
      enqueue = null;
    };
  }, []);

  function settle(confirmed: boolean) {
    request?.resolve(confirmed);
    setRequest(null);
  }

  return (
    <AlertDialog open={request !== null} onOpenChange={(open) => !open && settle(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="font-medium tracking-tight">{request?.title}</AlertDialogTitle>
          <AlertDialogDescription>{request?.description || "This can't be undone."}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={() => settle(false)}>Cancel</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={() => settle(true)}>
            {request?.confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
