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

interface ConfirmRequest {
  message: string;
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
export function confirmAction(message: string): Promise<boolean> {
  if (!enqueue) return Promise.resolve(window.confirm(message));
  const push = enqueue;
  return new Promise((resolve) => push({ message, resolve }));
}

function splitMessage(message: string) {
  const end = message.indexOf("?");
  const title = end === -1 ? message : message.slice(0, end + 1);
  const description = end === -1 ? "" : message.slice(end + 1).trim();
  const verb = /^(\w+)/.exec(title)?.[1] ?? "Confirm";
  return { title, description, verb };
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

  const { title, description, verb } = splitMessage(request?.message ?? "");

  return (
    <AlertDialog open={request !== null} onOpenChange={(open) => !open && settle(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="font-medium tracking-tight">{title}</AlertDialogTitle>
          <AlertDialogDescription>{description || "This can't be undone."}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={() => settle(false)}>Cancel</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={() => settle(true)}>
            {verb}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
