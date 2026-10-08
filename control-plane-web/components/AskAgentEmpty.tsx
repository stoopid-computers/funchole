import Link from "next/link";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { TerminalIcon } from "@/components/icons";

// Simple-mode empty state: nothing here yet, and the next step is to ask the
// coding agent rather than fill in a form.
export function AskAgentEmpty({ title, description }: { title: string; description: string }) {
  return (
    <EmptyState
      title={title}
      description={description}
      action={
        <Button variant="primary" size="sm" asChild>
          <Link href="/api-keys">
            <TerminalIcon className="h-4 w-4" />
            Connect your agent
          </Link>
        </Button>
      }
    />
  );
}
