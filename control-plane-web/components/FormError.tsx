import type { ReactNode } from "react";
import { CircleAlertIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { cn } from "@/lib/utils";

// Inline error shown next to the form or list that produced it.
export function FormError({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <Alert variant="destructive" className={cn("border-danger/25 bg-danger/10", className)}>
      <CircleAlertIcon />
      <AlertDescription className="text-danger">{children}</AlertDescription>
    </Alert>
  );
}
